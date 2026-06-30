package com.crossask.eval.model;

import java.util.List;

/**
 * 单题评测结果。
 */
public class EvalResult {

    private EvalCase evalCase;
    private String answer;
    private List<SourceInfo> actualSources;
    private List<ProductInfo> actualProducts;
    private String error;

    // 指标
    private Double recallAt5;       // null = N/A
    private Double mrr;             // null = N/A
    private boolean toolCallStrict;
    private boolean toolCallLoose;
    private double keywordHitRate;

    public EvalCase getEvalCase() { return evalCase; }
    public void setEvalCase(EvalCase evalCase) { this.evalCase = evalCase; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public List<SourceInfo> getActualSources() { return actualSources; }
    public void setActualSources(List<SourceInfo> actualSources) { this.actualSources = actualSources; }

    public List<ProductInfo> getActualProducts() { return actualProducts; }
    public void setActualProducts(List<ProductInfo> actualProducts) { this.actualProducts = actualProducts; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public Double getRecallAt5() { return recallAt5; }
    public void setRecallAt5(Double recallAt5) { this.recallAt5 = recallAt5; }

    public Double getMrr() { return mrr; }
    public void setMrr(Double mrr) { this.mrr = mrr; }

    public boolean isToolCallStrict() { return toolCallStrict; }
    public void setToolCallStrict(boolean toolCallStrict) { this.toolCallStrict = toolCallStrict; }

    public boolean isToolCallLoose() { return toolCallLoose; }
    public void setToolCallLoose(boolean toolCallLoose) { this.toolCallLoose = toolCallLoose; }

    public double getKeywordHitRate() { return keywordHitRate; }
    public void setKeywordHitRate(double keywordHitRate) { this.keywordHitRate = keywordHitRate; }

    public boolean isError() { return error != null && !error.isBlank(); }

    /** 实际调用的工具集合（从 AskResponse 反推）。 */
    public java.util.Set<String> getActualTools() {
        java.util.Set<String> tools = new java.util.TreeSet<>();
        if (actualSources != null && !actualSources.isEmpty()) tools.add("searchDocs");
        if (actualProducts != null && !actualProducts.isEmpty()) tools.add("queryProducts");
        return tools;
    }

    public static class SourceInfo {
        private String sourceUrl;
        private String sourceTitle;
        public String getSourceUrl() { return sourceUrl; }
        public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
        public String getSourceTitle() { return sourceTitle; }
        public void setSourceTitle(String sourceTitle) { this.sourceTitle = sourceTitle; }
    }

    public static class ProductInfo {
        private String title;
        private String price;
        private String sellerName;
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getPrice() { return price; }
        public void setPrice(String price) { this.price = price; }
        public String getSellerName() { return sellerName; }
        public void setSellerName(String sellerName) { this.sellerName = sellerName; }
    }
}
