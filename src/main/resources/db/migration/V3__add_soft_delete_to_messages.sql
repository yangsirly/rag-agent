-- V3: 工业级软删除改造，消除 ON DELETE CASCADE 大事务风险
-- messages 增加 deleted_at，查询一律过滤 deleted_at IS NULL
ALTER TABLE messages ADD COLUMN deleted_at DATETIME(6) NULL COMMENT '软删除时间，NULL=有效';
CREATE INDEX idx_messages_conversation_deleted ON messages (conversation_id, deleted_at, id);
-- 将外键从 CASCADE 改为 RESTRICT，删除由业务层先软删消息再删会话完成
ALTER TABLE messages DROP FOREIGN KEY fk_messages_conversation;
ALTER TABLE messages ADD CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE RESTRICT;
