package com.crossask.common.model;

import java.math.BigDecimal;

/**
 * 商品对外 DTO（v0.7 引入）。
 * 用于：
 * 1. LLM Function Calling 的 query_products 工具返回值（喂给 LLM）
 * 2. AskResponse.products 字段（返回给前端）
 * <p>
 * 与实体 {@link Product} 解耦，剔除 id/itemId/createdAt 等内部字段，避免给 LLM 噪声、给前端冗余。
 */
public record ProductItem(
        String title,
        BigDecimal price,
        String currency,
        String conditionText,
        String brand,
        String shippingText,
        Boolean freeShipping,
        String sellerName,
        String sourceUrl) {

    /** 从实体快捷构造。 */
    public static ProductItem of(Product p) {
        return new ProductItem(
                p.getTitle(),
                p.getPrice(),
                p.getCurrency(),
                p.getConditionText(),
                p.getBrand(),
                p.getShippingText(),
                p.getFreeShipping(),
                p.getSellerName(),
                p.getSourceUrl()
        );
    }
}
