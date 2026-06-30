package com.crossask.eval;

import com.crossask.eval.model.EvalCase;
import com.crossask.eval.model.EvalResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * v0.8 指标计算器。
 * 实现 Recall@5 / MRR / Tool-call accuracy (strict + loose) / Keyword hit rate。
 */
@Component
public class MetricCalculator {

    /**
     * Recall@5 = |expected ∩ actual| / |expected|
     * expected 为空时返回 null（N/A）。
     */
    public Double recallAt5(List<String> expectedUrls, List<String> actualUrls) {
        if (expectedUrls == null || expectedUrls.isEmpty()) return null;
        if (actualUrls == null || actualUrls.isEmpty()) return 0.0;
        Set<String> expectedSet = Set.copyOf(expectedUrls);
        Set<String> actualSet = Set.copyOf(actualUrls);
        long hits = expectedSet.stream().filter(actualSet::contains).count();
        return (double) hits / expectedSet.size();
    }

    /**
     * MRR = 1 / rank_of_first_hit（1-based）；未命中返回 0。
     * expected 为空时返回 null（N/A）。
     */
    public Double mrr(List<String> expectedUrls, List<String> actualUrls) {
        if (expectedUrls == null || expectedUrls.isEmpty()) return null;
        if (actualUrls == null || actualUrls.isEmpty()) return 0.0;
        Set<String> expectedSet = Set.copyOf(expectedUrls);
        for (int i = 0; i < actualUrls.size(); i++) {
            if (expectedSet.contains(actualUrls.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * Tool-call strict：实际工具集合 == 期望工具集合。
     */
    public boolean toolCallStrict(Set<String> actualTools, List<String> expectedTools) {
        Set<String> expectedSet = expectedTools == null ? Set.of() : Set.copyOf(expectedTools);
        return actualTools.equals(expectedSet);
    }

    /**
     * Tool-call loose：实际工具集合 ⊆ 期望工具集合。
     */
    public boolean toolCallLoose(Set<String> actualTools, List<String> expectedTools) {
        Set<String> expectedSet = expectedTools == null ? Set.of() : Set.copyOf(expectedTools);
        return expectedSet.containsAll(actualTools);
    }

    /**
     * Keyword hit rate = |命中关键词| / |期望关键词|（大小写不敏感）。
     */
    public double keywordHitRate(String answer, List<String> expectedKeywords) {
        if (expectedKeywords == null || expectedKeywords.isEmpty()) return 1.0;
        if (answer == null || answer.isBlank()) return 0.0;
        String lowerAnswer = answer.toLowerCase();
        long hits = expectedKeywords.stream()
                .filter(kw -> kw != null && !kw.isBlank())
                .filter(kw -> lowerAnswer.contains(kw.toLowerCase()))
                .count();
        return (double) hits / expectedKeywords.size();
    }

    /** 计算单题所有指标并填充到 result。 */
    public void compute(EvalResult result) {
        EvalCase ec = result.getEvalCase();

        List<String> actualUrls = result.getActualSources() == null
                ? List.of()
                : result.getActualSources().stream().map(EvalResult.SourceInfo::getSourceUrl).toList();

        result.setRecallAt5(recallAt5(ec.getExpectedSourceUrls(), actualUrls));
        result.setMrr(mrr(ec.getExpectedSourceUrls(), actualUrls));
        result.setToolCallStrict(toolCallStrict(result.getActualTools(), ec.getExpectedTools()));
        result.setToolCallLoose(toolCallLoose(result.getActualTools(), ec.getExpectedTools()));
        result.setKeywordHitRate(keywordHitRate(result.getAnswer(), ec.getExpectedKeywords()));
    }
}
