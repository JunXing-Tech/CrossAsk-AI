package com.crossask.api.product;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.crossask.common.mapper.ProductMapper;
import com.crossask.common.model.Product;
import com.crossask.common.model.ProductItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

/**
 * v0.7 商品查询服务（v1.0 增强：同义词扩展 + keyword 分词 AND LIKE）。
 */
@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductMapper productMapper;
    private final ProductQueryProperties props;
    private final SynonymExpander synonymExpander;

    public ProductService(ProductMapper productMapper, ProductQueryProperties props,
                          SynonymExpander synonymExpander) {
        this.productMapper = productMapper;
        this.props = props;
        this.synonymExpander = synonymExpander;
    }

    /**
     * 按条件查询商品。所有参数均可为 null（表示不过滤）。
     * v1.0：keyword 做同义词扩展 + 分词 AND LIKE。
     */
    public List<ProductItem> query(String keyword,
                                   String brand,
                                   BigDecimal minPrice,
                                   BigDecimal maxPrice,
                                   String conditionText,
                                   Boolean freeShippingOnly,
                                   Integer limit) {
        LambdaQueryWrapper<Product> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            // v1.0：层 1 同义词扩展，层 2 分词 AND LIKE
            List<String> synonyms = synonymExpander.expand(keyword);
            qw.and(wrapper -> {
                for (int i = 0; i < synonyms.size(); i++) {
                    if (i > 0) {
                        wrapper.or();
                    }
                    applyTokenLike(wrapper, synonyms.get(i));
                }
            });
        }
        if (StringUtils.hasText(brand)) {
            qw.eq(Product::getBrand, brand);
        }
        if (minPrice != null) {
            qw.ge(Product::getPrice, minPrice);
        }
        if (maxPrice != null) {
            qw.le(Product::getPrice, maxPrice);
        }
        if (StringUtils.hasText(conditionText)) {
            qw.like(Product::getConditionText, conditionText);
        }
        if (Boolean.TRUE.equals(freeShippingOnly)) {
            qw.eq(Product::getFreeShipping, true);
        }

        int effectiveLimit = clampLimit(limit);
        qw.orderByAsc(Product::getPrice);
        qw.last("LIMIT " + effectiveLimit);

        List<Product> rows = productMapper.selectList(qw);
        log.info("Product query: keyword={}, brand={}, price=[{},{}], condition={}, freeOnly={}, limit={} -> {} rows",
                keyword, brand, minPrice, maxPrice, conditionText, freeShippingOnly, effectiveLimit, rows.size());
        return rows.stream().map(ProductItem::of).toList();
    }

    /**
     * 对一个词（可能是多词短语）按空格分词后 AND LIKE。
     * 单词时退化为单个 LIKE（与 v0.7 一致）。
     * 多词时用 nested(...) 分组，内部多个 .like() 默认 AND 连接。
     */
    private void applyTokenLike(LambdaQueryWrapper<Product> wrapper, String phrase) {
        String[] tokens = phrase.trim().split("\\s+");
        if (tokens.length == 1) {
            wrapper.like(Product::getTitle, tokens[0]);
        } else {
            // 多词：用 and(consumer) 分组，内部 like 默认 AND
            wrapper.and(w -> {
                for (String token : tokens) {
                    w.like(Product::getTitle, token);
                }
            });
        }
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) return props.getDefaultLimit();
        return Math.min(limit, props.getMaxLimit());
    }
}
