package com.crossask.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crossask.common.model.Product;
import org.apache.ibatis.annotations.Mapper;

/**
 * v0.7 商品 Mapper（放 common 模块）。
 * ingestion 模块写入、api 模块读取共用。
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}
