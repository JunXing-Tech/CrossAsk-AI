package com.crossask.eval;

import com.crossask.eval.config.EvalProperties;
import com.crossask.eval.model.EvalCase;
import com.crossask.eval.model.EvalResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v0.8 Eval 主流程：读取评测集 → 逐题 HTTP 调 /ask → 计算指标 → 写报告。
 */
@Component
public class EvalRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private final EvalProperties props;
    private final MetricCalculator metricCalculator;
    private final ReportWriter reportWriter;
    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;
    private final HttpClient httpClient;

    public EvalRunner(EvalProperties props,
                      MetricCalculator metricCalculator,
                      ReportWriter reportWriter) {
        this.props = props;
        this.metricCalculator = metricCalculator;
        this.reportWriter = reportWriter;
        this.yamlMapper = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        this.jsonMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void run(String... args) {
        log.info("=== Eval 启动 ===");
        log.info("API URL: {}", props.getApiUrl());
        log.info("评测集: {}", props.getEvalSet());

        // 1. 加载评测集
        List<EvalCase> cases = loadEvalSet();
        if (cases.isEmpty()) {
            log.error("评测集为空，退出");
            return;
        }
        log.info("加载 {} 题评测用例", cases.size());

        // 2. 逐题执行
        List<EvalResult> results = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            log.info("--- {} [{}] {} ---", evalCase.getId(), evalCase.getCategory(), evalCase.getQuestion());
            EvalResult result = executeOne(evalCase);
            results.add(result);
            log.info("  工具: {} (期望: {}), Recall@5={}, Keyword={:.2f}",
                    result.getActualTools(), evalCase.getExpectedTools(),
                    result.getRecallAt5() == null ? "N/A" : String.format("%.2f", result.getRecallAt5()),
                    result.getKeywordHitRate());
        }

        // 3. 写报告
        String reportPath = reportWriter.write(results);
        log.info("=== Eval 完成，报告: {} ===", reportPath);
    }

    @SuppressWarnings("unchecked")
    private List<EvalCase> loadEvalSet() {
        try {
            ClassPathResource resource = new ClassPathResource(props.getEvalSet());
            if (!resource.exists()) {
                log.error("评测集文件不存在: {}", props.getEvalSet());
                return List.of();
            }
            Map<String, Object> root = yamlMapper.readValue(resource.getInputStream(), Map.class);
            List<Map<String, Object>> casesList = (List<Map<String, Object>>) root.get("cases");
            List<EvalCase> cases = new ArrayList<>();
            for (Map<String, Object> c : casesList) {
                cases.add(yamlMapper.convertValue(c, EvalCase.class));
            }
            return cases;
        } catch (Exception e) {
            log.error("加载评测集失败", e);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private EvalResult executeOne(EvalCase evalCase) {
        EvalResult result = new EvalResult();
        result.setEvalCase(evalCase);

        try {
            // 构造请求体
            String reqBody = jsonMapper.writeValueAsString(Map.of("question", evalCase.getQuestion()));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.getApiUrl()))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(reqBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                result.setError("HTTP " + response.statusCode() + ": " + response.body());
                return result;
            }

            // 解析 AskResponse
            Map<String, Object> resp = jsonMapper.readValue(response.body(), Map.class);
            String answer = (String) resp.getOrDefault("answer", "");
            result.setAnswer(answer);

            // 解析 sources
            List<Map<String, Object>> sourcesList = (List<Map<String, Object>>) resp.getOrDefault("sources", List.of());
            List<EvalResult.SourceInfo> sources = new ArrayList<>();
            for (Map<String, Object> s : sourcesList) {
                EvalResult.SourceInfo si = new EvalResult.SourceInfo();
                si.setSourceUrl((String) s.get("sourceUrl"));
                si.setSourceTitle((String) s.get("sourceTitle"));
                sources.add(si);
            }
            result.setActualSources(sources);

            // 解析 products
            List<Map<String, Object>> productsList = (List<Map<String, Object>>) resp.getOrDefault("products", List.of());
            List<EvalResult.ProductInfo> products = new ArrayList<>();
            for (Map<String, Object> p : productsList) {
                EvalResult.ProductInfo pi = new EvalResult.ProductInfo();
                pi.setTitle((String) p.get("title"));
                pi.setPrice(p.get("price") != null ? String.valueOf(p.get("price")) : "");
                pi.setSellerName((String) p.get("sellerName"));
                products.add(pi);
            }
            result.setActualProducts(products);

            // 计算指标
            metricCalculator.compute(result);

        } catch (Exception e) {
            result.setError(e.getMessage());
            log.error("  执行失败: {}", e.getMessage());
        }

        return result;
    }
}
