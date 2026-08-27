-- V5: 会话软删除
-- 工业级重构 V3 把 messages 外键改为 ON DELETE RESTRICT 并对 messages 做软删除，
-- 但 conversations 仍是物理删除——软删的消息行仍引用会话，RESTRICT 外键会阻止
-- 删除会话（删除任何带消息的会话都会抛外键约束错误 → 500）。
-- 修正：会话也改为软删除，行保留以维持外键完整性；所有查询过滤 deleted_at IS NULL。
-- 这样既满足"删除同一会话后再次删除返回 404"的契约，又消除了级联删除大事务。
ALTER TABLE conversations ADD COLUMN deleted_at DATETIME(6) NULL COMMENT '软删除时间，NULL=有效';
-- 列表按 (user_id, updated_at) 过滤软删会话；为避免软删行干扰，索引覆盖 deleted_at。
CREATE INDEX idx_conversations_user_updated ON conversations (user_id, deleted_at, updated_at);
