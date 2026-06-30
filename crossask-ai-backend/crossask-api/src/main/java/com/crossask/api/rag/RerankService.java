package com.crossask.api.rag;

import com.crossask.common.config.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 调 DashScope rerank API 对召回结果做二次精排。
 *
 * <p>API: POST https://dashscope.aliyuncs.com/compatible-api/v1/reranks
 * <p>请求体（OpenAI 兼容，扁平结构）：
 * <pre>
 * {
 *   "model": "qwen3-rerank",
 *   "query": "...",
 *   "documents": ["...", "..."],
 *   "top_n": 5
 * }
 * </pre>
 * <p>响应：
 * <pre>
 * {
 *   "output": { "results": [ { "index": 0, "relevance_score": 0.93 } ] },
 *   "usage": { "total_tokens": 0 },
 *   "request_id": "..."
 * }
 * </pre>
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private static final String RERANK_ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-api/v1/reranks";

    private final String apiKey;
    private final RagProperties ragProps;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RerankService(@Value("${spring.ai.dashscope.api-key}") String apiKey,
                         RagProperties ragProps) {
        this.apiKey = apiKey;
        this.ragProps = ragProps;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 调 rerank 接口。返回值是「按相关性降序排列的原始下标」列表，长度 ≤ topN。
     * 失败时返回空列表，调用方应降级使用原始召回顺序。
     */
    @SuppressWarnings("unchecked")
    public List<Integer> rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        int effectiveTopN = Math.min(topN, documents.size());

        try {
            Map<String, Object> body = Map.of(
                    "model", ragProps.getRerank().getModel(),
                    "query", query,
                    "documents", documents,
                    "top_n", effectiveTopN);

            String json = objectMapper.writeValueAsString(body);

            HttpResponse<String> resp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(RERANK_ENDPOINT))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                log.warn("Rerank 调用失败: status={}, body={}", resp.statusCode(), resp.body());
                return List.of();
            }

            log.debug("Rerank 响应: {}", resp.body());

            Map<String, Object> result = objectMapper.readValue(resp.body(), Map.class);
            // OpenAI 兼容接口直接返回 { "results": [...], "usage": {...} }
            // 部分版本/网关可能包一层 output，做兼容
            List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
            if (results == null) {
                Map<String, Object> output = (Map<String, Object>) result.get("output");
                if (output != null) {
                    results = (List<Map<String, Object>>) output.get("results");
                }
            }
            if (results == null) {
                log.warn("Rerank 响应解析失败，未找到 results 字段: {}", resp.body());
                return List.of();
            }

            List<Integer> indices = new ArrayList<>(results.size());
            for (Map<String, Object> item : results) {
                Number index = (Number) item.get("index");
                if (index != null) {
                    indices.add(index.intValue());
                }
            }
            log.info("Rerank 完成: 输入 {} 条 → 返回 {} 条", documents.size(), indices.size());
            return indices;
        } catch (Exception e) {
            log.warn("Rerank 调用异常，将降级使用向量召回顺序", e);
            return List.of();
        }
    }
}
