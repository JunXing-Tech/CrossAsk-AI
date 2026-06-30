package com.crossask.ingestion.crawler;

import com.crossask.common.constants.ContentTypes;
import com.crossask.common.model.RawDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 爬虫：读取 urls.txt → Jsoup 下载 → 根据 URL 域名自动标注 content_type → 输出 List<RawDocument>
 */
@Component
public class Crawler {

    private static final Logger log = LoggerFactory.getLogger(Crawler.class);

    private static final int TIMEOUT_MS = 30000;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36";

    /**
     * 从 classpath 读取 urls.txt，逐个下载
     */
    public List<RawDocument> crawl() {
        List<String> urls = loadUrls();
        log.info("urls.txt 加载完成，共 {} 个 URL", urls.size());

        List<RawDocument> documents = new ArrayList<>();

        for (String url : urls) {
            try {
                RawDocument doc = download(url);
                if (doc != null) {
                    documents.add(doc);
                    log.info("下载成功: {} (title={})", url, doc.getTitle());
                }
            } catch (Exception e) {
                // 单个 URL 失败时记录日志并跳过，不中断流程
                log.warn("下载失败，跳过: {} - {}", url, e.getMessage());
            }
        }

        log.info("爬虫完成: 成功 {}/{}", documents.size(), urls.size());
        return documents;
    }

    /**
     * 用 Jsoup 下载单个 URL
     * 不设 Accept-Language（让 eBay 返回中文），用字节流模式让 Jsoup 自动检测编码
     */
    private RawDocument download(String url) throws IOException {
        org.jsoup.Connection.Response response = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .followRedirects(true)
                .execute();

        // 用 Jsoup 自动检测编码解析（meta charset / BOM / Content-Type）
        Document doc = response.parse();

        String title = doc.title();
        String html = doc.html();
        String contentType = resolveContentType(url);

        return new RawDocument(url, title, html, contentType);
    }

    /**
     * 根据 URL 域名自动标注 content_type
     * - usps.com → logistic
     * - ebay.com 含 international/shipping/customs → logistic
     * - ebay.com/help/policies 和其他 → policy
     */
    private String resolveContentType(String url) {
        String lower = url.toLowerCase();
        // USPS 物流类
        if (lower.contains("usps.com")) {
            return ContentTypes.LOGISTIC;
        }
        // eBay 物流/国际运输类
        if (lower.contains("ebay.com") && (lower.contains("international") || lower.contains("shipping") || lower.contains("customs"))) {
            return ContentTypes.LOGISTIC;
        }
        // 其他 → policy
        return ContentTypes.POLICY;
    }

    /**
     * 从 classpath 读取 urls.txt，跳过空行和 # 注释行
     */
    private List<String> loadUrls() {
        try {
            Path path = new ClassPathResource("urls.txt").getFile().toPath();
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("读取 urls.txt 失败", e);
        }
    }
}
