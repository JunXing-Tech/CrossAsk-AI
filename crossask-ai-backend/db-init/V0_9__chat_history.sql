-- ============================================================
-- CrossAsk v0.9 多轮对话历史表
-- ------------------------------------------------------------
-- 1. 用 root 或具备 CREATE 权限的账号执行本文件
-- 2. 库 crossask_ai 已由 V0_7__products.sql 创建
-- 3. chat_history 表存对话历史，7 天后由定时任务清理
-- ============================================================

USE crossask_ai;

CREATE TABLE IF NOT EXISTS chat_history (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    session_id  VARCHAR(64)  NOT NULL                COMMENT '会话 ID（UUID，前端生成）',
    turn_index  INT          NOT NULL                COMMENT '轮次序号（从 1 开始）',
    role        VARCHAR(16)  NOT NULL                COMMENT 'user / assistant',
    content     TEXT         NOT NULL                COMMENT '消息内容',
    created_at  DATETIME              DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_session_turn (session_id, turn_index),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
