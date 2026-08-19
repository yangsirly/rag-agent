-- 里程碑 3：会话与消息表。
-- 会话归属用户；消息归属会话。删除会话时通过外键级联删除消息，避免孤儿行。

CREATE TABLE conversations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会话主键',

    user_id BIGINT UNSIGNED NOT NULL COMMENT '会话所属用户',
    title VARCHAR(100) NOT NULL COMMENT '会话标题，1～100 字',

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    -- 历史会话列表：当前用户按最近更新时间倒序
    KEY idx_conversations_user_updated (user_id, updated_at, id),

    CONSTRAINT fk_conversations_user
        FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '聊天会话表';

CREATE TABLE messages (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '消息主键',

    conversation_id BIGINT UNSIGNED NOT NULL COMMENT '所属会话',

    -- 消息角色，与用户角色 CUSTOMER/EDITOR 不同
    role VARCHAR(20) NOT NULL COMMENT 'USER 或 ASSISTANT',

    -- 第一阶段最长 10000 字；用 TEXT 避免 utf8mb4 下 VARCHAR 过长限制
    content TEXT NOT NULL COMMENT '消息正文',

    -- USER 消息：客户端一次发送动作的幂等键；ASSISTANT 消息必须为 NULL
    client_message_id CHAR(36) NULL COMMENT '客户端 UUID，仅 USER 消息填写',

    -- ASSISTANT 消息：指向对应 USER 消息；USER 消息必须为 NULL
    reply_to_message_id BIGINT UNSIGNED NULL COMMENT '模板回复所对应的 USER 消息 id',

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    -- 历史消息按时间正序；分页查询可利用该复合索引
    KEY idx_messages_conversation_created (conversation_id, created_at, id),

    -- 同一会话内同一 client_message_id 只能有一条 USER 消息（并发幂等的最终保证）
    -- MySQL 唯一索引允许多个 NULL，因此 ASSISTANT 行的 client_message_id 为 NULL 不会冲突
    CONSTRAINT uk_messages_conversation_client_message
        UNIQUE (conversation_id, client_message_id),

    -- 一条 USER 消息最多对应一条 ASSISTANT 回复
    CONSTRAINT uk_messages_reply_to
        UNIQUE (reply_to_message_id),

    CONSTRAINT fk_messages_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversations (id)
        ON DELETE CASCADE,

    CONSTRAINT ck_messages_role
        CHECK (role IN ('USER', 'ASSISTANT')),

    -- 用 CHECK 固化角色字段形态，避免业务层漏校验导致脏数据
    CONSTRAINT ck_messages_role_fields CHECK (
        (role = 'USER'
            AND client_message_id IS NOT NULL
            AND reply_to_message_id IS NULL)
        OR
        (role = 'ASSISTANT'
            AND client_message_id IS NULL
            AND reply_to_message_id IS NOT NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '聊天消息表';
