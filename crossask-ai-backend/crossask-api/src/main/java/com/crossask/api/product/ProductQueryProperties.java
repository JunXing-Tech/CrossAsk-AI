package com.crossask.api.product;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 商品查询配置（crossask.product.query.*）。
 */
@ConfigurationProperties(prefix = "crossask.product.query")
public class ProductQueryProperties {

    private int defaultLimit = 5;
    private int maxLimit = 10;

    public int getDefaultLimit() { return defaultLimit; }
    public void setDefaultLimit(int defaultLimit) { this.defaultLimit = defaultLimit; }

    public int getMaxLimit() { return maxLimit; }
    public void setMaxLimit(int maxLimit) { this.maxLimit = maxLimit; }
}