package com.crossask.ingestion.product;

import com.crossask.common.model.Product;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * v0.7 商品数据加载器（从本地 mock JSON 装载）。
 * <p>
 * 原始设计为 eBay 页面爬虫，但 eBay 对自动化请求返回 403（国内 IP 无法绕过反爬）。
 * 改用 `products-mock.json`（含 25 条贴近真实 eBay listing 的 Mock 数据）。
 * 代码骨架保留，后续若环境允许可恢复真实爬取 {@link ProductCrawler#crawl()}。
 * <p>
 * JSON 字段与 Product 实体字段一一对应，从 classpath 加载。
 */
@Component
public class ProductCrawler {

    private static final Logger log = LoggerFactory.getLogger(ProductCrawler.class);

    private static final String MOCK_PATH = "products-mock.json";

    private final ObjectMapper objectMapper;

    public ProductCrawler() {
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public List<Product> crawl() {
        // 从 classpath 加载 JSON
        try {
            ClassPathResource resource = new ClassPathResource(MOCK_PATH);
            if (!resource.exists()) {
                log.warn("Mock JSON 文件 {} 不存在，返回空列表", MOCK_PATH);
                return List.of();
            }

            List<ProductDto> dtos;
            try (InputStream is = resource.getInputStream()) {
                dtos = objectMapper.readValue(is, new TypeReference<List<ProductDto>>() {});
            }

            log.info("Mock JSON 加载完成: {} 条商品", dtos.size());

            List<Product> products = new ArrayList<>();
            for (ProductDto dto : dtos) {
                Product p = new Product();
                p.setItemId(dto.itemId);
                p.setTitle(dto.title);
                p.setBrand(dto.brand);
                p.setPrice(dto.price == null ? null : BigDecimal.valueOf(dto.price));
                p.setCurrency(dto.currency != null ? dto.currency : "USD");
                p.setConditionText(dto.conditionText);
                p.setShippingText(dto.shippingText);
                p.setFreeShipping(dto.freeShipping);
                p.setSellerName(dto.sellerName);
                p.setSellerFeedbackPct(dto.sellerFeedbackPct == null ? null : BigDecimal.valueOf(dto.sellerFeedbackPct));
                p.setItemLocation(dto.itemLocation);
                p.setImageUrl(dto.imageUrl);
                p.setSourceUrl(dto.sourceUrl);
                p.setCrawledAt(LocalDateTime.now());
                products.add(p);
            }

            return products;
        } catch (IOException e) {
            log.error("加载 Mock JSON 失败: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /** DTO 对应 JSON 结构。 */
    private static class ProductDto {
        public String itemId;
        public String title;
        public String brand;
        public Double price;
        public String currency;
        public String conditionText;
        public String shippingText;
        public Boolean freeShipping;
        public String sellerName;
        public Double sellerFeedbackPct;
        public String itemLocation;
        public String imageUrl;
        public String sourceUrl;
    }
}