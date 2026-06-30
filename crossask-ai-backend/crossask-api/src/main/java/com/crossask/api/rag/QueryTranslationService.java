package com.crossask.api.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v1.0 Query 翻译服务。
 * <p>
 * 把中文 query 翻译成英文，供 RagSearchService 的 sparse 路使用。
 * 用 plain ChatClient（不带 tools），加本地缓存避免重复翻译。
 * 英文 query（ASCII >80%）跳过翻译。
 */
@Service
public class QueryTranslationService {

    private static final Logger log = LoggerFactory.getLogger(QueryTranslationService.class);

    private static final String TRANSLATE_PROMPT = """
            Translate the following text to English. Output ONLY the translation, nothing else.
            
            Text: %s
            """;

    private final ChatClient translateClient;

    /** 简易 TTL 缓存：key=query, value=翻译结果+时间戳 */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final long TTL_MS = 3600_000L; // 1 hour
    private static final int MAX_SIZE = 500;
    private final AtomicLong cacheHits = new AtomicLong(0);

    public QueryTranslationService(ChatClient.Builder builder) {
        // 构造一个 plain ChatClient（不带 tools/system prompt）
        this.translateClient = builder.build();
    }

    /**
     * 翻译 query 为英文。英文 query 直接返回。
     * 翻译失败/超时降级为返回原 query。
     */
    public String translateToEnglish(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }

        // 英文检测：ASCII 字符占比 >80% 跳过翻译
        if (isEnglish(query)) {
            log.debug("query 为英文，跳过翻译: {}", query);
            return query;
        }

        // 查缓存
        CacheEntry cached = cache.get(query);
        if (cached != null && !cached.isExpired()) {
            cacheHits.incrementAndGet();
            log.debug("翻译缓存命中: {}", query);
            return cached.value;
        }

        // 调 LLM 翻译
        try {
            String translated = translateClient.prompt()
                    .user(String.format(TRANSLATE_PROMPT, query))
                    .call()
                    .content();
            if (translated != null) {
                translated = translated.trim();
                // 清理可能的引号包裹
                if (translated.startsWith("\"") && translated.endsWith("\"")) {
                    translated = translated.substring(1, translated.length() - 1);
                }
            }
            if (translated == null || translated.isBlank()) {
                log.warn("翻译结果为空，降级用原 query: {}", query);
                return query;
            }

            // 写缓存
            putCache(query, translated);
            log.info("翻译: '{}' -> '{}'", query, translated);
            return translated;
        } catch (Exception e) {
            log.warn("翻译失败，降级用原 query: {} - {}", query, e.getMessage());
            return query;
        }
    }

    private boolean isEnglish(String text) {
        int asciiCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 128) {
                asciiCount++;
            }
        }
        return (double) asciiCount / text.length() > 0.8;
    }

    private void putCache(String key, String value) {
        // 简易容量控制：超过 maxSize 时清空（不做 LRU，RAG 场景够用）
        if (cache.size() >= MAX_SIZE) {
            cache.clear();
        }
        cache.put(key, new CacheEntry(value, System.currentTimeMillis()));
    }

    private static class CacheEntry {
        final String value;
        final long timestamp;
        CacheEntry(String value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > TTL_MS;
        }
    }
}
