package com.crossask.ingestion.cleaner;

import com.crossask.common.model.RawDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 清洗器：按 URL 去重 → 去掉 nav/footer/header 等噪音元素（保留正文 HTML 结构含 h1/h2）
 */
@Component
public class Cleaner {

    private static final Logger log = LoggerFactory.getLogger(Cleaner.class);

    /**
     * 噪音 class 关键词
     */
    private static final String[] NOISE_CLASS_KEYWORDS = {"menu", "sidebar", "breadcrumb", "copyright"};

    /**
     * 按 URL 去重 → 去噪音元素
     */
    public List<RawDocument> clean(List<RawDocument> documents) {
        // 按 URL 去重（保留第一个出现的）
        Map<String, RawDocument> deduped = new LinkedHashMap<>();
        for (RawDocument doc : documents) {
            deduped.putIfAbsent(doc.getUrl(), doc);
        }
        int dedupCount = documents.size() - deduped.size();
        if (dedupCount > 0) {
            log.info("去重: 移除 {} 个重复 URL", dedupCount);
        }

        // 去噪音元素
        List<RawDocument> cleaned = deduped.values().stream()
                .map(this::removeNoise)
                .toList();

        log.info("清洗完成: {} 篇文档", cleaned.size());
        return cleaned;
    }

    /**
     * 删除 nav/footer/header 及噪音 class 元素，保留正文 HTML 结构
     */
    private RawDocument removeNoise(RawDocument doc) {
        Document jsoupDoc = Jsoup.parse(doc.getHtmlContent());

        // 删除标签
        jsoupDoc.select("nav, footer, header, script, style, noscript, iframe").remove();

        // 删除 class 含噪音关键词的元素
        for (String keyword : NOISE_CLASS_KEYWORDS) {
            Elements noiseElements = jsoupDoc.getElementsByAttributeValueMatching(
                    "class", "(?i).*" + keyword + ".*");
            noiseElements.remove();
        }

        doc.setHtmlContent(jsoupDoc.body().html());
        return doc;
    }
}
