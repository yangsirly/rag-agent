-- V4: 工业级扩展列：消息状态与用户登录防刷
-- messages 状态预留异步化：DONE=模板已落库，PENDING=等待模型（当前模板阶段恒为 DONE）
ALTER TABLE messages ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'DONE' COMMENT 'DONE/PENDING';
ALTER TABLE messages ADD CONSTRAINT ck_messages_status CHECK (status IN ('DONE', 'PENDING'));
-- users 登录防刷兜底（Redis 为主，DB 为备）
ALTER TABLE users ADD COLUMN failed_login_count INT NOT NULL DEFAULT 0 COMMENT '连续失败次数';
ALTER TABLE users ADD COLUMN lock_until DATETIME(6) NULL COMMENT '锁定至';
