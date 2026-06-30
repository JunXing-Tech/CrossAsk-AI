package com.crossask.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 混合检索配置（v0.6）：dense+sparse 双路召回 + RRF 融合。
 * ingestion 只用 {@link #enabled}；api 用全部字段。
 */
@ConfigurationProperties(prefix = "crossask.hybrid")
public class HybridProperties {

    /** 是否启用混合检索；false 时退回纯 dense（v0.5 行为） */
    private boolean enabled = true;

    /** dense 路 prefetch 候选数（仅 api 用） */
    private int densePrefetchLimit = 30;

    /** sparse 路 prefetch 候选数（仅 api 用） */
    private int sparsePrefetchLimit = 30;

    /** 融合算法，目前固定为 rrf（仅 api 用） */
    private String fusion = "rrf";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDensePrefetchLimit() {
        return densePrefetchLimit;
    }

    public void setDensePrefetchLimit(int densePrefetchLimit) {
        this.densePrefetchLimit = densePrefetchLimit;
    }

    public int getSparsePrefetchLimit() {
        return sparsePrefetchLimit;
    }

    public void setSparsePrefetchLimit(int sparsePrefetchLimit) {
        this.sparsePrefetchLimit = sparsePrefetchLimit;
    }

    public String getFusion() {
        return fusion;
    }

    public void setFusion(String fusion) {
        this.fusion = fusion;
    }
}
