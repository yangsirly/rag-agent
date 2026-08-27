CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(254),
    phone VARCHAR(32),
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    failed_login_count INT NOT NULL DEFAULT 0,
    lock_until TIMESTAMP NULL,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_phone UNIQUE (phone),
    CONSTRAINT ck_users_contact CHECK (email IS NOT NULL OR phone IS NOT NULL),
    CONSTRAINT ck_users_role CHECK (role IN ('CUSTOMER', 'EDITOR')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- 测试库使用 H2：与 Flyway V3/V4/V5 语义对齐，语法按 H2 简化（无 UNSIGNED / COMMENT）。
CREATE TABLE IF NOT EXISTS conversations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT fk_conversations_user
        FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE IF NOT EXISTS messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content CLOB NOT NULL,
    client_message_id CHAR(36),
    reply_to_message_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DONE',
    CONSTRAINT uk_messages_conversation_client_message
        UNIQUE (conversation_id, client_message_id),
    CONSTRAINT uk_messages_reply_to
        UNIQUE (reply_to_message_id),
    CONSTRAINT fk_messages_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversations (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_messages_role
        CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT ck_messages_role_fields CHECK (
        (role = 'USER'
            AND client_message_id IS NOT NULL
            AND reply_to_message_id IS NULL)
        OR
        (role = 'ASSISTANT'
            AND client_message_id IS NULL
            AND reply_to_message_id IS NOT NULL)
    ),
    CONSTRAINT ck_messages_status CHECK (status IN ('DONE', 'PENDING'))
);
