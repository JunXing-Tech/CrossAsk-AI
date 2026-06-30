package com.crossask.api.product;

import com.crossask.common.model.ProductItem;
import com.crossask.common.model.ToolCallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * v0.7 商品查询工具。
 */
@Component
public class ProductQueryTool {

    private static final Logger log = LoggerFactory.getLogger(ProductQueryTool.class);

    private final ProductService productService;

    public ProductQueryTool(ProductService productService) {
        this.productService = productService;
    }

    @Tool(description =
            "Query the e-commerce product catalog (MySQL). " +
            "Use this for questions about specific product price, availability, brand, seller, " +
            "or finding products matching criteria. " +
            "Do NOT use this for policy/shipping/return questions.")
    public List<ProductItem> queryProducts(
            @ToolParam(description = "Free-text keyword (matches product title)", required = false) String keyword,
            @ToolParam(description = "Brand filter (e.g. Apple, Samsung, Sony)", required = false) String brand,
            @ToolParam(description = "Maximum price in USD", required = false) BigDecimal maxPrice,
            @ToolParam(description = "Minimum price in USD", required = false) BigDecimal minPrice,
            @ToolParam(description = "Item condition: New / Pre-Owned / Refurbished", required = false) String conditionText,
            @ToolParam(description = "true to only return items with free shipping", required = false) Boolean freeShippingOnly,
            @ToolParam(description = "Max results (default 5, max 10)", required = false) Integer limit) {

        try {
            List<ProductItem> result = productService.query(keyword, brand, minPrice, maxPrice,
                    conditionText, freeShippingOnly, limit);
            ToolCallContext.addProducts(result);
            log.info("queryProducts: keyword={}, brand={}, maxPrice={}, -> {} results", keyword, brand, maxPrice, result.size());
            return result;
        } catch (Exception e) {
            log.error("queryProducts 异常", e);
            return List.of();
        }
    }
}