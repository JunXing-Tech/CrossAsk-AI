-- ============================================================
-- CrossAsk v1.0 商品中英同义词表
-- ------------------------------------------------------------
-- 1. 用有权限的账号在 crossask_ai 库执行
-- 2. ProductService 查询时自动把中文关键词扩展为英文同义词
-- ============================================================

USE crossask_ai;

CREATE TABLE IF NOT EXISTS product_synonyms (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    keyword_cn   VARCHAR(32)  NOT NULL                COMMENT '中文关键词',
    synonyms_en  VARCHAR(256) NOT NULL                COMMENT '英文同义词（逗号分隔）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_keyword_cn (keyword_cn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 初始数据：覆盖项目 25 条商品的 10 个类目
INSERT INTO product_synonyms (keyword_cn, synonyms_en) VALUES
    ('耳机',    'headphones,earbuds,AirPods,headset'),
    ('手机',    'iPhone,phone,smartphone,Samsung,Galaxy,Pixel'),
    ('电脑',    'MacBook,laptop,notebook'),
    ('阅读器',  'Kindle,Paperwhite,ereader'),
    ('手表',    'Watch'),
    ('平板',    'iPad,tablet'),
    ('降噪',    'Noise Cancelling'),
    ('蓝牙',    'Bluetooth,Wireless'),
    ('翻新',    'Refurbished'),
    ('全新',    'Brand New,New')
ON DUPLICATE KEY UPDATE synonyms_en = VALUES(synonyms_en);
