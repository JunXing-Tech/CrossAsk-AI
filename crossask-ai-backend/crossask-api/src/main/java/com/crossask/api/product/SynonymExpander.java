package com.crossask.api.product;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.crossask.common.mapper.ProductSynonymMapper;
import com.crossask.common.model.ProductSynonym;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1.0 同义词扩展服务。
 * <p>
 * 中文 keyword → 查 MySQL product_synonyms 表 → 返回英文同义词列表。
 * 英文 keyword（ASCII >80%）直接返回 [keyword]。
 * 缓存：ConcurrentHashMap + TTL=1h, maxSize=200。
 */
@Component
public class SynonymExpander {

    private static final Logger log = LoggerFactory.getLogger(SynonymExpander.class);

    private final ProductSynonymMapper synonymMapper;

    /** 缓存：key=keyword, value=同义词列表+时间戳 */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final long TTL_MS = 3600_000L; // 1 hour
    private static final int MAX_SIZE = 200;

    public SynonymExpander(ProductSynonymMapper synonymMapper) {
        this.synonymMapper = synonymMapper;
    }

    /**
     * 扩展 keyword 为同义词列表。
     * @return 含原 keyword + 同义词；无同义词则返回 [keyword]
     */
    public List<String> expand(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }

        // 英文 keyword 不查同义词表
        if (isEnglish(keyword)) {
            return List.of(keyword);
        }

        // 查缓存
        CacheEntry cached = cache.get(keyword);
        if (cached != null && !cached.isExpired()) {
            return cached.value;
        }

        // 查 MySQL：用 LIKE 匹配 keyword_cn（不完全匹配，覆盖"降噪耳机"匹配"耳机"）
        List<String> result = new ArrayList<>();
        try {
            List<ProductSynonym> rows = synonymMapper.selectList(
                    new LambdaQueryWrapper<ProductSynonym>()
                            .like(ProductSynonym::getKeywordCn, keyword));

            for (ProductSynonym row : rows) {
                String[] synonyms = row.getSynonymsEn().split(",");
                for (String s : synonyms) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty() && !result.contains(trimmed)) {
                        result.add(trimmed);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("查同义词表失败，降级为原 keyword: {} - {}", keyword, e.getMessage());
            return List.of(keyword);
        }

        if (result.isEmpty()) {
            result.add(keyword);
        }

        // 写缓存
        if (cache.size() >= MAX_SIZE) {
            cache.clear();
        }
        cache.put(keyword, new CacheEntry(List.copyOf(result), System.currentTimeMillis()));

        log.info("同义词扩展: '{}' -> {}", keyword, result);
        return result;
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

    private static class CacheEntry {
        final List<String> value;
        final long timestamp;
        CacheEntry(List<String> value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > TTL_MS;
        }
    }
}
