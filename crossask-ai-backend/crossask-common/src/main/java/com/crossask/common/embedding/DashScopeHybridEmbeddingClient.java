package com.crossask.common.embedding;

import com.crossask.common.model.HybridEmbedding;
import com.crossask.common.model.SparseEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 调 DashScope 原生 text-embedding REST 接口，同时取回 dense + sparse 双向量。
 *
 * <p>API: POST https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding
 * <p>请求体（DashScope 原生格式，需要 input/parameters 包一层）：
 * <pre>
 * {
 *   "model": "text-embedding-v4",
 *   "input": { "texts": ["..."] },
 *   "parameters": {
 *     "dimension": 1024,
 *     "output_type": "dense&sparse",
 *     "text_type": "document"   // 或 "query"
 *   }
 * }
 * </pre>
 *
 * <p>OpenAI 兼容接口不支持 output_type/text_type，因此 v0.6 不能复用
 * {@code spring.ai.dashscope.embedding} 自动配置出来的 EmbeddingModel。
 */
@Component
public class DashScopeHybridEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeHybridEmbeddingClient.class);

    private static final String ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

    private static final String MODEL = "text-embedding-v4";

    private final String apiKey;
    private final int dimension;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DashScopeHybridEmbeddingClient(
            @Value("${spring.ai.dashscope.api-key}") String apiKey,
            @Value("${spring.ai.dashscope.embedding.options.dimensions:1024}") int dimension) {
        this.apiKey = apiKey;
        this.dimension = dimension;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 文本 → HybridEmbedding。
     *
     * @param text     输入文本
     * @param textType "document"（入库）或 "query"（查询）
     * @return dense + sparse 混合向量。调用失败抛 RuntimeException 让上层决定降级。
     */
    @SuppressWarnings("unchecked")
    public HybridEmbedding embed(String text, String textType) {
        try {
            Map<String, Object> body = Map.of(
                    "model", MODEL,
                    "input", Map.of("texts", List.of(text)),
                    "parameters", Map.of(
                            "dimension", dimension,
                            "output_type", "dense&sparse",
                            "text_type", textType));

            String json = objectMapper.writeValueAsString(body);

            HttpResponse<String> resp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(ENDPOINT))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                throw new RuntimeException("DashScope embedding 失败: " + resp.statusCode()
                        + " - " + resp.body());
            }

            Map<String, Object> root = objectMapper.readValue(resp.body(), Map.class);
            Map<String, Object> output = (Map<String, Object>) root.get("output");
            if (output == null) {
                throw new RuntimeException("DashScope 响应缺少 output: " + resp.body());
            }
            List<Map<String, Object>> embeddings = (List<Map<String, Object>>) output.get("embeddings");
            if (embeddings == null || embeddings.isEmpty()) {
                throw new RuntimeException("DashScope 响应 embeddings 为空: " + resp.body());
            }

            Map<String, Object> first = embeddings.get(0);
            float[] dense = toFloatArray((List<Number>) first.get("embedding"));
            List<SparseEntry> sparse = parseSparse((List<Map<String, Object>>) first.get("sparse_embedding"));
            return new HybridEmbedding(dense, sparse);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("DashScope embedding 异常", e);
        }
    }

    private static float[] toFloatArray(List<Number> values) {
        if (values == null) {
            return new float[0];
        }
        float[] out = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i).floatValue();
        }
        return out;
    }

    private static List<SparseEntry> parseSparse(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<SparseEntry> result = new ArrayList<>(raw.size());
        for (Map<String, Object> item : raw) {
            Number idx = (Number) item.get("index");
            Number val = (Number) item.get("value");
            if (idx == null || val == null) {
                continue;
            }
            result.add(new SparseEntry(idx.intValue(), val.floatValue()));
        }
        return result;
    }
}
