-- v1.2 会话隔离：为 chat_history 增加 client_id 列，按浏览器隔离会话
ALTER TABLE chat_history ADD COLUMN client_id VARCHAR(36) DEFAULT NULL;
CREATE INDEX idx_chat_history_client_id ON chat_history(client_id);
