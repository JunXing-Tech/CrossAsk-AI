package com.crossask.eval;

import com.crossask.eval.config.EvalProperties;
import com.crossask.eval.model.EvalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 生成 Markdown 评测报告。
 */
@Component
public class ReportWriter {

    private static final Logger log = LoggerFactory.getLogger(ReportWriter.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final EvalProperties props;

    public ReportWriter(EvalProperties props) {
        this.props = props;
    }

    public String write(List<EvalResult> results) {
        String timestamp = LocalDateTime.now().format(TS_FMT);
        String fileName = timestamp + ".md";
        Path reportDir = Paths.get(props.getReportDir());
        Path reportFile = reportDir.resolve(fileName);

        try {
            Files.createDirectories(reportDir);
            String md = buildMarkdown(results, timestamp);
            Files.writeString(reportFile, md);
            log.info("报告已写入: {}", reportFile.toAbsolutePath());
            return reportFile.toAbsolutePath().toString();
        } catch (IOException e) {
            log.error("写报告失败", e);
            return null;
        }
    }

    private String buildMarkdown(List<EvalResult> results, String timestamp) {
        StringBuilder sb = new StringBuilder();

        // 头部
        sb.append("# CrossAsk Eval 报告\n\n");
        sb.append("- **时间**: ").append(timestamp).append("\n");
        sb.append("- **API 配置快照**: ").append(props.getApiConfigSnapshot()).append("\n");
        sb.append("- **题数**: ").append(results.size()).append("\n");
        sb.append("- **LLM-judge**: ").append(props.getJudge().isEnabled() ? "启用" : "关闭").append("\n\n");

        // 总体指标
        sb.append("## 总体指标\n\n");
        sb.append("| 指标 | 值 |\n|---|---|\n");
        sb.append(String.format("| Recall@5 | %.4f |\n", avgRecall(results)));
        sb.append(String.format("| MRR | %.4f |\n", avgMrr(results)));
        sb.append(String.format("| Tool-call Accuracy (strict) | %.4f |\n", toolStrictRate(results)));
        sb.append(String.format("| Tool-call Accuracy (loose) | %.4f |\n", toolLooseRate(results)));
        sb.append(String.format("| Keyword Hit Rate | %.4f |\n", avgKeyword(results)));
        sb.append(String.format("| ERROR 题数 | %d |\n\n", results.stream().filter(EvalResult::isError).count()));

        // 分类指标
        sb.append("## 分类指标\n\n");
        Map<String, List<EvalResult>> byCategory = results.stream()
                .collect(Collectors.groupingBy(r -> r.getEvalCase().getCategory()));
        sb.append("| 类别 | 题数 | Recall@5 | MRR | Tool(strict) | Tool(loose) | Keyword |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (String cat : List.of("docs", "products", "mixed", "fallback")) {
            List<EvalResult> catResults = byCategory.getOrDefault(cat, List.of());
            if (catResults.isEmpty()) continue;
            sb.append(String.format("| %s | %d | %.4f | %.4f | %.4f | %.4f | %.4f |\n",
                    cat, catResults.size(),
                    avgRecall(catResults), avgMrr(catResults),
                    toolStrictRate(catResults), toolLooseRate(catResults),
                    avgKeyword(catResults)));
        }
        sb.append("\n");

        // 逐题详情
        sb.append("## 逐题详情\n\n");
        sb.append("| # | 问题 | 类别 | 工具(实际) | 工具(期望) | Recall@5 | MRR | Tool(s) | Tool(l) | Keyword | 状态 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (EvalResult r : results) {
            sb.append(String.format("| %s | %s | %s | %s | %s | %s | %s | %s | %s | %.2f | %s |\n",
                    r.getEvalCase().getId(),
                    truncate(r.getEvalCase().getQuestion(), 30),
                    r.getEvalCase().getCategory(),
                    r.getActualTools(),
                    r.getEvalCase().getExpectedTools(),
                    r.getRecallAt5() == null ? "N/A" : String.format("%.2f", r.getRecallAt5()),
                    r.getMrr() == null ? "N/A" : String.format("%.2f", r.getMrr()),
                    r.isToolCallStrict() ? "✅" : "❌",
                    r.isToolCallLoose() ? "✅" : "❌",
                    r.getKeywordHitRate(),
                    r.isError() ? "ERROR" : "OK"));
        }
        sb.append("\n");

        // 失败案例
        List<EvalResult> failures = results.stream()
                .filter(r -> !r.isError() && (!r.isToolCallLoose() || r.getRecallAt5() != null && r.getRecallAt5() < 0.5 || r.getKeywordHitRate() < 0.3))
                .toList();
        if (!failures.isEmpty()) {
            sb.append("## 失败/低分案例\n\n");
            for (EvalResult r : failures) {
                sb.append("### ").append(r.getEvalCase().getId()).append(": ").append(r.getEvalCase().getQuestion()).append("\n\n");
                sb.append("- 类别: ").append(r.getEvalCase().getCategory()).append("\n");
                sb.append("- 期望工具: ").append(r.getEvalCase().getExpectedTools()).append("\n");
                sb.append("- 实际工具: ").append(r.getActualTools()).append("\n");
                sb.append("- Recall@5: ").append(r.getRecallAt5() == null ? "N/A" : String.format("%.2f", r.getRecallAt5())).append("\n");
                sb.append("- Keyword Hit Rate: ").append(String.format("%.2f", r.getKeywordHitRate())).append("\n");
                sb.append("- Answer: ").append(truncate(r.getAnswer(), 200)).append("\n\n");
            }
        }

        return sb.toString();
    }

    private double avgRecall(List<EvalResult> results) {
        return results.stream().filter(r -> r.getRecallAt5() != null)
                .mapToDouble(EvalResult::getRecallAt5).average().orElse(0);
    }

    private double avgMrr(List<EvalResult> results) {
        return results.stream().filter(r -> r.getMrr() != null)
                .mapToDouble(EvalResult::getMrr).average().orElse(0);
    }

    private double toolStrictRate(List<EvalResult> results) {
        return results.stream().filter(r -> !r.isError())
                .mapToDouble(r -> r.isToolCallStrict() ? 1 : 0).average().orElse(0);
    }

    private double toolLooseRate(List<EvalResult> results) {
        return results.stream().filter(r -> !r.isError())
                .mapToDouble(r -> r.isToolCallLoose() ? 1 : 0).average().orElse(0);
    }

    private double avgKeyword(List<EvalResult> results) {
        return results.stream().filter(r -> !r.isError())
                .mapToDouble(EvalResult::getKeywordHitRate).average().orElse(0);
    }

    private String truncate(String s, int len) {
        if (s == null) return "";
        s = s.replace("|", "\\|").replace("\n", " ");
        return s.length() <= len ? s : s.substring(0, len) + "...";
    }
}
