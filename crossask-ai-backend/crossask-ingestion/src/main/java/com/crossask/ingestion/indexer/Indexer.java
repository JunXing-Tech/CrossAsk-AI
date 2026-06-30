package com.crossask.ingestion.indexer;

import com.crossask.common.config.QdrantProperties;
import com.crossask.common.embedding.DashScopeHybridEmbeddingClient;
import com.crossask.common.model.Document;
import com.crossask.common.model.HybridEmbedding;
import com.crossask.common.model.SparseEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v0.6 混合检索索引器：
 * 1. 创建 named vectors collection（dense 1024 维 + sparse 含 idf modifier）
 * 2. 按 source_url 删除旧数据（增量重跑去重）
 * 3. 调 DashScope text-embedding-v4（output_type=dense&sparse, text_type=document）
 * 4. 入库 point：vector = { dense: [...], sparse: { indices, values } }
 */
@Component
public class Indexer {

    private static final Logger log = LoggerFactory.getLogger(Indexer.class);

    private static final String DENSE_VECTOR_NAME = "dense";
    private static final String SPARSE_VECTOR_NAME = "sparse";

    private final DashScopeHybridEmbeddingClient hybridClient;
    private final QdrantProperties qdrantProps;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public Indexer(DashScopeHybridEmbeddingClient hybridClient, QdrantProperties qdrantProps) {
        this.hybridClient = hybridClient;
        this.qdrantProps = qdrantProps;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public int index(List<Document> documents) {
        ensureCollection();
        deleteOldDocuments(documents);

        int successCount = 0;
        int sparseEmptyCount = 0;
        for (Document doc : documents) {
            try {
                HybridEmbedding emb = hybridClient.embed(doc.getContent(), "document");
                if (emb.sparse().isEmpty()) {
                    sparseEmptyCount++;
                }
                upsertPoint(doc, emb);
                successCount++;
            } catch (Exception e) {
                log.warn("Embedding/入库失败，跳过 chunk: sourceUrl={}, chunkIndex={} - {}",
                        doc.getSourceUrl(), doc.getChunkIndex(), e.getMessage());
            }
        }

        log.info("入库完成: 成功 {}/{}，其中 sparse 为空 {} 条", successCount, documents.size(), sparseEmptyCount);
        return successCount;
    }

    /**
     * 创建 collection（若已存在则跳过）。v0.6 改为 named vectors（dense + sparse）。
     */
    private void ensureCollection() {
        try {
            String url = baseUrl() + "/collections/" + qdrantProps.getCollection();

            HttpResponse<String> resp = httpClient.send(
                    HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                log.info("Collection '{}' 已存在，跳过创建", qdrantProps.getCollection());
                return;
            }

            Map<String, Object> body = Map.of(
                    "vectors", Map.of(
                            DENSE_VECTOR_NAME, Map.of(
                                    "size", qdrantProps.getDimension(),
                                    "distance", qdrantProps.getDistance())),
                    "sparse_vectors", Map.of(
                            SPARSE_VECTOR_NAME, Map.of(
                                    "modifier", "idf")));

            String json = objectMapper.writeValueAsString(body);
            resp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(json))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                log.info("Collection '{}' 创建成功 (dense dim={}, distance={}, sparse modifier=idf)",
                        qdrantProps.getCollection(), qdrantProps.getDimension(), qdrantProps.getDistance());
            } else {
                log.error("Collection 创建失败: {} - {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.error("Collection 创建异常", e);
        }
    }

    /**
     * 按 source_url 删除旧数据（增量重跑去重）
     */
    private void deleteOldDocuments(List<Document> documents) {
        List<String> sourceUrls = documents.stream()
                .map(Document::getSourceUrl)
                .distinct()
                .toList();

        for (String url : sourceUrls) {
            try {
                Map<String, Object> body = Map.of(
                        "filter", Map.of(
                                "must", List.of(Map.of(
                                        "key", "source_url",
                                        "match", Map.of("value", url)))));

                String json = objectMapper.writeValueAsString(body);
                String endpoint = baseUrl() + "/collections/" + qdrantProps.getCollection()
                        + "/points/delete?wait=true";

                httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(endpoint))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                log.warn("删除旧数据失败: sourceUrl={} - {}", url, e.getMessage());
            }
        }
        log.info("旧数据删除完成（按 {} 个 source_url）", sourceUrls.size());
    }

    /**
     * 入库一个 point：vector = { dense: [...], sparse: { indices, values } }
     */
    private void upsertPoint(Document doc, HybridEmbedding emb) throws Exception {
        String pointId = UUID.randomUUID().toString();

        Map<String, Object> payload = new HashMap<>();
        payload.put("doc_id", pointId);
        payload.put("source_url", doc.getSourceUrl());
        payload.put("source_title", doc.getSourceTitle());
        payload.put("chunk_index", doc.getChunkIndex());
        payload.put("content", doc.getContent());
        payload.put("content_type", doc.getContentType());

        // dense
        List<Float> denseList = new ArrayList<>(emb.dense().length);
        for (float f : emb.dense()) {
            denseList.add(f);
        }

        // sparse → 拆成 indices/values 两个数组
        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put(DENSE_VECTOR_NAME, denseList);
        if (!emb.sparse().isEmpty()) {
            List<Integer> indices = new ArrayList<>(emb.sparse().size());
            List<Float> values = new ArrayList<>(emb.sparse().size());
            for (SparseEntry e : emb.sparse()) {
                indices.add(e.index());
                values.add(e.value());
            }
            vector.put(SPARSE_VECTOR_NAME, Map.of(
                    "indices", indices,
                    "values", values));
        }

        Map<String, Object> body = Map.of(
                "points", List.of(Map.of(
                        "id", pointId,
                        "vector", vector,
                        "payload", payload)));

        String json = objectMapper.writeValueAsString(body);
        String endpoint = baseUrl() + "/collections/" + qdrantProps.getCollection()
                + "/points?wait=true";

        HttpResponse<String> resp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            throw new RuntimeException("Qdrant upsert 失败: " + resp.statusCode() + " - " + resp.body());
        }
    }

    private String baseUrl() {
        return "http://" + qdrantProps.getHost() + ":" + qdrantProps.getPort();
    }
}
