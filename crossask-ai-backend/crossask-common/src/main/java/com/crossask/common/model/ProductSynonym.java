package com.crossask.common.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * v1.0 商品中英同义词表实体。
 */
@TableName("product_synonyms")
public class ProductSynonym {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String keywordCn;
    private String synonymsEn;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getKeywordCn() { return keywordCn; }
    public void setKeywordCn(String keywordCn) { this.keywordCn = keywordCn; }

    public String getSynonymsEn() { return synonymsEn; }
    public void setSynonymsEn(String synonymsEn) { this.synonymsEn = synonymsEn; }
}
