package com.crossask.ingestion.product;

import com.crossask.common.model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * v0.7 商品入库流水线：ProductCrawler → ProductImporter（MySQL upsert）。
 * <p>
 * 启动方式：{@code --spring.profiles.active=products}。
 */
@Component
@Profile("products")
public class ProductIngestionRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductIngestionRunner.class);

    private final ProductCrawler crawler;
    private final ProductImporter importer;

    public ProductIngestionRunner(ProductCrawler crawler, ProductImporter importer) {
        this.crawler = crawler;
        this.importer = importer;
    }

    @Override
    public void run(String... args) {
        log.info("=== [products] Product Ingestion 启动 ===");
        List<Product> products = crawler.crawl();
        log.info("Crawler 输出: {} 条 Product", products.size());

        int n = importer.upsert(products);
        log.info("Importer 输出: 写入 {} 条（含 insert + update）", n);

        log.info("=== [products] Product Ingestion 完成 ===");
    }
}
