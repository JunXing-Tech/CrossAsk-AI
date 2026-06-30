package com.crossask.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 检索配置（仅 crossask-api 使用）
 */
@ConfigurationProperties(prefix = "crossask.rag")
public class RagProperties {

    /** 向量召回阶段返回数（rerank 的输入候选数） */
    private int retrieveTopK = 15;

    /** rerank 精排后取前 N 个（给 LLM 的上下文数量） */
    private int rerankTopK = 5;

    /** Qdrant 召回阶段的 score_threshold */
    private double similarityThreshold = 0.2;

    private Rerank rerank = new Rerank();

    public int getRetrieveTopK() {
        return retrieveTopK;
    }

    public void setRetrieveTopK(int retrieveTopK) {
        this.retrieveTopK = retrieveTopK;
    }

    public int getRerankTopK() {
        return rerankTopK;
    }

    public void setRerankTopK(int rerankTopK) {
        this.rerankTopK = rerankTopK;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    public Rerank getRerank() {
        return rerank;
    }

    public void setRerank(Rerank rerank) {
        this.rerank = rerank;
    }

    /**
     * rerank 子配置
     */
    public static class Rerank {

        /** 是否启用 rerank（false 时退回 MVP 行为：直接用向量召回的前 rerankTopK 个） */
        private boolean enabled = true;

        /** rerank 模型名（DashScope） */
        private String model = "qwen3-rerank";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }
}
