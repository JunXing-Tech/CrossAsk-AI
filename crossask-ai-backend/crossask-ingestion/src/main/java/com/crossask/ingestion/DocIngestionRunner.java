package com.crossask.ingestion;

import com.crossask.common.model.Document;
import com.crossask.common.model.RawDocument;
import com.crossask.ingestion.cleaner.Cleaner;
import com.crossask.ingestion.crawler.Crawler;
import com.crossask.ingestion.indexer.Indexer;
import com.crossask.ingestion.splitter.Splitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * v0.6 文档入库流水线：crawler → cleaner → splitter → indexer。
 * <p>
 * v0.7 重命名自原 {@code IngestionRunner}，加 {@code @Profile("docs")} 用于
 * 区分文档 / 商品两条 ingestion 线（详见 Agent.md 14.5）。
 * <p>
 * 启动方式：{@code --spring.profiles.active=docs}。
 */
@Component
@Profile("docs")
public class DocIngestionRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DocIngestionRunner.class);

    private final Crawler crawler;
    private final Cleaner cleaner;
    private final Splitter splitter;
    private final Indexer indexer;

    public DocIngestionRunner(Crawler crawler, Cleaner cleaner, Splitter splitter, Indexer indexer) {
        this.crawler = crawler;
        this.cleaner = cleaner;
        this.splitter = splitter;
        this.indexer = indexer;
    }

    @Override
    public void run(String... args) {
        log.info("=== [docs] Ingestion 流水线启动 ===");

        List<RawDocument> rawDocuments = crawler.crawl();
        log.info("Crawler 输出: {} 篇 RawDocument", rawDocuments.size());

        List<RawDocument> cleanedDocuments = cleaner.clean(rawDocuments);
        log.info("Cleaner 输出: {} 篇清洗后文档", cleanedDocuments.size());

        List<Document> chunks = splitter.split(cleanedDocuments);
        log.info("Splitter 输出: {} 个 Document chunk", chunks.size());

        int indexed = indexer.index(chunks);
        log.info("Indexer 输出: {}/{} 个 chunk 入库成功", indexed, chunks.size());

        log.info("=== [docs] Ingestion 流水线完成 ===");
    }
}
