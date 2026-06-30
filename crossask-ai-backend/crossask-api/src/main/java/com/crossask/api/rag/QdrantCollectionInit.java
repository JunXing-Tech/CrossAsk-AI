package com.crossask.api.rag;

import com.crossask.common.config.QdrantProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * 应用启动时检查 Qdrant collection 满足 v0.6 要求：
 * - 含 named vector "dense"，size = 配置的 dimension
 * - 含 sparse vector "sparse"
 * 不满足则报错退出，提示先运行 ingestion 重建。
 */
@Component
@Order(1)
public class QdrantCollectionInit implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(QdrantCollectionInit.class);

    private static final String DENSE_VECTOR_NAME = "dense";
    private static final String SPARSE_VECTOR_NAME = "sparse";

    private final QdrantProperties qdrantProps;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public QdrantCollectionInit(QdrantProperties qdrantProps) {
        this.qdrantProps = qdrantProps;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void run(String... args) {
        try {
            String url = baseUrl() + "/collections/" + qdrantProps.getCollection();
            HttpResponse<String> resp = httpClient.send(
                    HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                throw new IllegalStateException("Collection '" + qdrantProps.getCollection()
                        + "' 不存在，请先运行 crossask-ingestion 入库数据");
            }

            Map<String, Object> root = objectMapper.readValue(resp.body(), Map.class);
            Map<String, Object> result = (Map<String, Object>) root.get("result");
            Map<String, Object> config = (Map<String, Object>) result.get("config");
            Map<String, Object> params = (Map<String, Object>) config.get("params");

            // 检查 dense vector
            Map<String, Object> vectors = (Map<String, Object>) params.get("vectors");
            if (vectors == null || !vectors.containsKey(DENSE_VECTOR_NAME)) {
                throw new IllegalStateException("Collection 缺少 named vector '" + DENSE_VECTOR_NAME
                        + "'，请删除 collection 后重新运行 ingestion");
            }
            Map<String, Object> dense = (Map<String, Object>) vectors.get(DENSE_VECTOR_NAME);
            int actualDim = ((Number) dense.get("size")).intValue();
            if (actualDim != qdrantProps.getDimension()) {
                throw new IllegalStateException("Collection dense 维度不匹配，期望 "
                        + qdrantProps.getDimension() + "，实际 " + actualDim);
            }

            // 检查 sparse vector
            Map<String, Object> sparseVectors = (Map<String, Object>) params.get("sparse_vectors");
            if (sparseVectors == null || !sparseVectors.containsKey(SPARSE_VECTOR_NAME)) {
                throw new IllegalStateException("Collection 缺少 sparse vector '" + SPARSE_VECTOR_NAME
                        + "'，请删除 collection 后重新运行 ingestion");
            }

            log.info("Collection '{}' 检查通过 (dense dim={}, sparse 已配置)",
                    qdrantProps.getCollection(), actualDim);
        } catch (IllegalStateException e) {
            log.error("Qdrant collection 校验失败: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Qdrant 连接失败: {}", e.getMessage());
            throw new IllegalStateException("Qdrant connection failed", e);
        }
    }

    private String baseUrl() {
        return "http://" + qdrantProps.getHost() + ":" + qdrantProps.getPort();
    }
}
