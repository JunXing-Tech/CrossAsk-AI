package com.crossask.ingestion.splitter;

import com.crossask.common.model.RawDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 切分器：按 h1/h2 切分 → 每个 chunk 去 HTML 标签转纯文本 → <100 字符丢弃
 * 无 h1/h2 时整篇文档作为一个 chunk
 */
@Component
public class Splitter {

    private static final Logger log = LoggerFactory.getLogger(Splitter.class);

    private static final int MIN_CHUNK_LENGTH = 100;

    /**
     * 对清洗后的 RawDocument 列表进行切分，输出 Document 列表
     */
    public List<com.crossask.common.model.Document> split(List<RawDocument> documents) {
        List<com.crossask.common.model.Document> chunks = new ArrayList<>();
        int totalChunks = 0;
        int discardedChunks = 0;

        for (RawDocument doc : documents) {
            List<com.crossask.common.model.Document> docChunks = splitOne(doc);
            for (com.crossask.common.model.Document chunk : docChunks) {
                if (chunk.getContent().length() < MIN_CHUNK_LENGTH) {
                    discardedChunks++;
                } else {
                    chunk.setChunkIndex(totalChunks);
                    chunks.add(chunk);
                    totalChunks++;
                }
            }
        }

        log.info("切分完成: 生成 {} 个 chunk（丢弃 {} 个短文本）", chunks.size(), discardedChunks);
        return chunks;
    }

    /**
     * 按 h1/h2 切单个文档
     */
    private List<com.crossask.common.model.Document> splitOne(RawDocument doc) {
        List<com.crossask.common.model.Document> result = new ArrayList<>();
        org.jsoup.nodes.Document jsoupDoc = Jsoup.parse(doc.getHtmlContent());

        // 尝试提取第一个 h1 作为 title（比 HTML <title> 更精确）
        String title = doc.getTitle();
        Elements h1Elements = jsoupDoc.select("h1");
        if (!h1Elements.isEmpty()) {
            String h1Text = h1Elements.first().text().trim();
            if (!h1Text.isEmpty()) {
                title = h1Text;
            }
        }

        // 按 h1/h2 切分
        Elements headings = jsoupDoc.select("h1, h2");
        if (headings.isEmpty()) {
            // 无 h1/h2：整篇文档作为一个 chunk
            String text = jsoupDoc.text();
            result.add(createChunk(doc, title, 0, text));
            return result;
        }

        // 有 h1/h2：每个标题到下一个标题之间的内容为一个 chunk
        for (int i = 0; i < headings.size(); i++) {
            Element heading = headings.get(i);
            StringBuilder content = new StringBuilder();
            content.append(heading.text()).append("\n");

            // 收集该标题后的所有兄弟元素，直到遇到下一个 h1/h2
            Element sibling = heading.nextElementSibling();
            while (sibling != null && !isHeading(sibling)) {
                content.append(sibling.text()).append("\n");
                sibling = sibling.nextElementSibling();
            }

            result.add(createChunk(doc, title, i, content.toString()));
        }

        return result;
    }

    private boolean isHeading(Element el) {
        String tag = el.tagName().toLowerCase();
        return "h1".equals(tag) || "h2".equals(tag);
    }

    private com.crossask.common.model.Document createChunk(RawDocument source, String title, int chunkIndex, String content) {
        com.crossask.common.model.Document doc = new com.crossask.common.model.Document();
        doc.setSourceUrl(source.getUrl());
        doc.setSourceTitle(title);
        doc.setChunkIndex(chunkIndex);
        doc.setContent(content);
        doc.setContentType(source.getContentType());
        return doc;
    }
}
