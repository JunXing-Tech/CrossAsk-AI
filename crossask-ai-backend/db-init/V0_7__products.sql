-- ============================================================
-- CrossAsk v0.7 数据库初始化脚本
-- ------------------------------------------------------------
-- 1. 用 root 或具备 CREATE 权限的账号执行本文件
-- 2. 已存在则跳过；不会清空数据
-- 3. crossask_ai 账号需要拥有 crossask 库的 ALL PRIVILEGES
--    可选授权（root 执行）：
--      GRANT ALL PRIVILEGES ON crossask.* TO 'crossask_ai'@'%';
--      FLUSH PRIVILEGES;
-- ============================================================

CREATE DATABASE IF NOT EXISTS crossask_ai
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE crossask_ai;

-- ------------------------------------------------------------
-- v0.7 商品表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS products (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    item_id               VARCHAR(64)  NOT NULL                COMMENT 'eBay item ID（URL 中提取）',
    title                 VARCHAR(512) NOT NULL                COMMENT '商品标题',
    brand                 VARCHAR(64)           DEFAULT NULL   COMMENT '品牌',
    price                 DECIMAL(10,2)         DEFAULT NULL   COMMENT '价格（USD）',
    currency              VARCHAR(8)            DEFAULT 'USD',
    condition_text        VARCHAR(32)           DEFAULT NULL   COMMENT 'New / Pre-Owned / Refurbished',
    shipping_text         VARCHAR(128)          DEFAULT NULL,
    free_shipping         TINYINT(1)            DEFAULT 0,
    seller_name           VARCHAR(128)          DEFAULT NULL,
    seller_feedback_pct   DECIMAL(5,2)          DEFAULT NULL,
    item_location         VARCHAR(128)          DEFAULT NULL,
    image_url             VARCHAR(512)          DEFAULT NULL,
    source_url            VARCHAR(512) NOT NULL,
    created_at            DATETIME              DEFAULT CURRENT_TIMESTAMP,
    crawled_at            DATETIME              DEFAULT NULL,
    updated_at            DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_item_id (item_id),
    KEY idx_brand (brand),
    KEY idx_price (price),
    FULLTEXT KEY ft_title (title) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
