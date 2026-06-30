package com.crossask.ingestion.product;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.crossask.common.mapper.ProductMapper;
import com.crossask.common.model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * v0.7 商品入库器：按 item_id 唯一索引做 upsert。
 */
@Component
public class ProductImporter {

    private static final Logger log = LoggerFactory.getLogger(ProductImporter.class);

    private final ProductMapper productMapper;

    public ProductImporter(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    /**
     * @return 实际 insert + update 的总条数
     */
    public int upsert(java.util.List<Product> products) {
        int n = 0;
        for (Product p : products) {
            Product existing = productMapper.selectOne(
                    new LambdaQueryWrapper<Product>().eq(Product::getItemId, p.getItemId()).last("LIMIT 1"));
            if (existing == null) {
                productMapper.insert(p);
                n++;
                log.debug("insert itemId={}", p.getItemId());
            } else {
                p.setId(existing.getId());
                productMapper.updateById(p);
                n++;
                log.debug("update itemId={}", p.getItemId());
            }
        }
        return n;
    }
}
