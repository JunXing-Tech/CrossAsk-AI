package com.crossask.api.rag;

import com.crossask.common.config.HybridProperties;
import com.crossask.common.config.QdrantProperties;
import com.crossask.common.config.RagProperties;
import com.crossask.common.embedding.DashScopeHybridEmbeddingClient;
import com.crossask.common.model.HybridEmbedding;
import com.crossask.common.model.Source;
import com.crossask.common.model.SparseEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * v1.0 重构：sparse 路 query 翻译 + 并行双 embedding。
 * <p>
 * 流程：
 * <ol>
 *   <li>CompletableFuture 1：embed(原 query) → 取 dense</li>
 *   <li>CompletableFuture 2：translateToEnglish(query) → embed(英文 query) → 取 sparse</li>
 *   <li>join → 组装 HybridEmbedding(dense, sparse) → Qdrant hybrid search → rerank</li>
 * </ol>
 * dense-only 模式不调翻译，直接 embed 一次。
 */
@Service
public class RagSearchService {

    private static final Logger log = LoggerFactory.getLogger(RagSearchService.class);

    private static final String DENSE_VECTOR_NAME = "dense";
    private static final String SPARSE_VECTOR_NAME = "sparse";

    private final DashScopeHybridEmbeddingClient hybridClient;
    private final QdrantProperties qdrantProps;
    private final RagProperties ragProps;
    private final HybridProperties hybridProps;
    private final RerankService rerankService;
    private final QueryTranslationService translationService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RagSearchService(DashScopeHybridEmbeddingClient hybridClient,
                            QdrantProperties qdrantProps,
                            RagProperties ragProps,
                            HybridProperties hybridProps,
                            RerankService rerankService,
                            QueryTranslationService translationService) {
        this.hybridClient = hybridClient;
        this.qdrantProps = qdrantProps;
        this.ragProps = ragProps;
        this.hybridProps = hybridProps;
        this.rerankService = rerankService;
        this.translationService = translationService;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /** 入参为用户原始问题，返回 rerank 后的 top N 个 Source（含 url/title/snippet）。 */
    public List<Source> search(String question) {
        HybridEmbedding emb;
        try {
            emb = buildHybridEmbedding(question);
        } catch (Exception e) {
            log.error("Embedding 调用失败", e);
            return List.of();
        }

        List<Map<String, Object>> hits = hybridSearch(emb);
        if (hits.isEmpty()) {
            log.info("Qdrant 无召回");
            return List.of();
        }

        List<Map<String, Object>> topHits = rerankHits(question, hits);

        List<Source> sources = new ArrayList<>();
        for (Map<String, Object> hit : topHits) {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) hit.get("payload");
            if (payload == null) continue;
            String content = (String) payload.get("content");
            String sourceUrl = (String) payload.get("source_url");
            String sourceTitle = (String) payload.get("source_title");
            String snippet = content == null ? "" : (content.length() > 400 ? content.substring(0, 400) : content);
            sources.add(new Source(sourceUrl, sourceTitle, snippet));
        }

        log.info("RAG 检索完成: question={}, recall={}, top={}", question, hits.size(), sources.size());
        return sources;
    }

    /**
     * v1.0：并行执行 dense embedding + (翻译 + sparse embedding)。
     * dense-only 模式直接 embed 一次。
     */
    private HybridEmbedding buildHybridEmbedding(String question) {
        if (!hybridProps.isEnabled()) {
            // dense-only：直接 embed 一次
            return hybridClient.embed(question, "query");
        }

        // hybrid 模式：并行执行
        CompletableFuture<float[]> denseFuture = CompletableFuture.supplyAsync(() -> {
            HybridEmbedding emb1 = hybridClient.embed(question, "query");
            return emb1.dense();
        });

        CompletableFuture<List<SparseEntry>> sparseFuture = CompletableFuture.supplyAsync(() -> {
            String translated = translationService.translateToEnglish(question);
            HybridEmbedding emb2 = hybridClient.embed(translated, "query");
            return emb2.sparse();
        });

        try {
            float[] dense = denseFuture.join();
            List<SparseEntry> sparse = sparseFuture.join();
            log.info("并行 embedding 完成: dense_dim={}, sparse_size={}", dense.length, sparse.size());
            return new HybridEmbedding(dense, sparse);
        } catch (Exception e) {
            log.warn("并行 embedding 失败，降级为 dense-only: {}", e.getMessage());
            // 降级：至少把 dense 拿到
            try {
                HybridEmbedding fallback = hybridClient.embed(question, "query");
                return new HybridEmbedding(fallback.dense(), List.of());
            } catch (Exception ex) {
                throw new RuntimeException("降级 embedding 也失败", ex);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> hybridSearch(HybridEmbedding emb) {
        try {
            List<Float> denseList = toFloatList(emb.dense());
            boolean useHybrid = hybridProps.isEnabled() && !emb.sparse().isEmpty();

            Map<String, Object> body;
            if (useHybrid) {
                Map<String, Object> sparseQuery = buildSparseQuery(emb.sparse());
                List<Map<String, Object>> prefetch = List.of(
                        Map.of(
                                "query", denseList,
                                "using", DENSE_VECTOR_NAME,
                                "limit", hybridProps.getDensePrefetchLimit()),
                        Map.of(
                                "query", sparseQuery,
                                "using", SPARSE_VECTOR_NAME,
                                "limit", hybridProps.getSparsePrefetchLimit()));
                body = Map.of(
                        "prefetch", prefetch,
                        "query", Map.of("fusion", hybridProps.getFusion()),
                        "limit", ragProps.getRetrieveTopK(),
                        "with_payload", true);
            } else {
                body = Map.of(
                        "query", denseList,
                        "using", DENSE_VECTOR_NAME,
                        "limit", ragProps.getRetrieveTopK(),
                        "with_payload", true);
            }

            String json = objectMapper.writeValueAsString(body);
            String endpoint = baseUrl() + "/collections/" + qdrantProps.getCollection() + "/points/query";

            HttpResponse<String> resp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(endpoint))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                log.error("Qdrant query 失败: {} - {}", resp.statusCode(), resp.body());
                return List.of();
            }

            Map<String, Object> root = objectMapper.readValue(resp.body(), Map.class);
            Object resultObj = root.get("result");
            List<Map<String, Object>> hits;
            if (resultObj instanceof Map) {
                Map<String, Object> resultMap = (Map<String, Object>) resultObj;
                hits = (List<Map<String, Object>>) resultMap.get("points");
            } else if (resultObj instanceof List) {
                hits = (List<Map<String, Object>>) resultObj;
            } else {
                hits = List.of();
            }
            if (hits == null) {
                hits = List.of();
            }
            log.info("Qdrant 检索完成: 模式={}, 返回 {} 条",
                    useHybrid ? "hybrid" : "dense-only", hits.size());
            return hits;
        } catch (Exception e) {
            log.error("Qdrant 检索异常", e);
            return List.of();
        }
    }

    private Map<String, Object> buildSparseQuery(List<SparseEntry> sparse) {
        List<Integer> indices = new ArrayList<>(sparse.size());
        List<Float> values = new ArrayList<>(sparse.size());
        for (SparseEntry e : sparse) {
            indices.add(e.index());
            values.add(e.value());
        }
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("indices", indices);
        q.put("values", values);
        return q;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rerankHits(String question, List<Map<String, Object>> hits) {
        int rerankTopK = ragProps.getRerankTopK();
        int fallbackSize = Math.min(rerankTopK, hits.size());

        if (!ragProps.getRerank().isEnabled()) {
            return hits.subList(0, fallbackSize);
        }

        List<String> documents = new ArrayList<>(hits.size());
        for (Map<String, Object> hit : hits) {
            Map<String, Object> payload = (Map<String, Object>) hit.get("payload");
            String content = payload == null ? "" : (String) payload.getOrDefault("content", "");
            documents.add(content);
        }

        List<Integer> rankedIndices = rerankService.rerank(question, documents, rerankTopK);
        if (rankedIndices.isEmpty()) {
            log.warn("Rerank 无结果，降级使用向量召回顺序");
            return hits.subList(0, fallbackSize);
        }

        List<Map<String, Object>> reranked = new ArrayList<>(rankedIndices.size());
        for (int idx : rankedIndices) {
            if (idx >= 0 && idx < hits.size()) {
                reranked.add(hits.get(idx));
            }
        }
        return reranked;
    }

    private static List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) {
            list.add(f);
        }
        return list;
    }

    private String baseUrl() {
        return "http://" + qdrantProps.getHost() + ":" + qdrantProps.getPort();
    }
}
