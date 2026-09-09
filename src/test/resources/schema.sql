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

CREATE TABLE IF NOT EXISTS refresh_sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash VARBINARY(32) NOT NULL,
    current_access_jti VARCHAR(36),
    current_access_expires_at TIMESTAMP NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL,
    CONSTRAINT uk_refresh_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_refresh_sessions_user_expires
    ON refresh_sessions (user_id, expires_at);
CREATE INDEX IF NOT EXISTS idx_refresh_sessions_expires
    ON refresh_sessions (expires_at);

-- 测试库使用 H2：与 Flyway V3/V4/V5/V6 语义对齐，语法按 H2 简化（无 UNSIGNED / COMMENT）。
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

CREATE TABLE IF NOT EXISTS knowledge_bases (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    creator_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    editor_ids JSON,
    reader_ids JSON,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT uk_knowledge_bases_creator_name UNIQUE (creator_id, name),
    CONSTRAINT fk_knowledge_bases_creator FOREIGN KEY (creator_id) REFERENCES users (id) ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS idx_knowledge_bases_creator_updated
    ON knowledge_bases (creator_id, deleted_at, updated_at);

CREATE TABLE IF NOT EXISTS documents (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    creator_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    summary VARCHAR(500),
    content CLOB NOT NULL,
    content_length INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,
    CONSTRAINT uk_documents_kb_title UNIQUE (knowledge_base_id, title),
    CONSTRAINT uk_documents_kb_id UNIQUE (knowledge_base_id, id),
    CONSTRAINT fk_documents_knowledge_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases (id) ON DELETE RESTRICT,
    CONSTRAINT fk_documents_creator FOREIGN KEY (creator_id) REFERENCES users (id) ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS idx_documents_kb_updated
    ON documents (knowledge_base_id, deleted_at, updated_at);

-- 与 Flyway V9 对齐：P2.1 先把 embedding 作为 JSON 持久化，由 Java 做 Exact Search。
CREATE TABLE IF NOT EXISTS document_chunks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content CLOB NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    embedding JSON,
    embedding_model VARCHAR(100),
    embedding_dimension INT,
    source_document_updated_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_document_chunks_document_version_index UNIQUE (document_id, source_document_updated_at, chunk_index),
    CONSTRAINT fk_document_chunks_document_scope
        FOREIGN KEY (knowledge_base_id, document_id)
        REFERENCES documents (knowledge_base_id, id)
        ON DELETE CASCADE,
    CONSTRAINT ck_document_chunks_offsets CHECK (end_offset > start_offset),
    CONSTRAINT ck_document_chunks_embedding_metadata CHECK (
        (embedding IS NULL AND embedding_model IS NULL AND embedding_dimension IS NULL)
        OR
        (embedding IS NOT NULL AND embedding_model IS NOT NULL AND embedding_dimension > 0)
    )
);
CREATE INDEX IF NOT EXISTS idx_document_chunks_kb_document_version
    ON document_chunks (knowledge_base_id, document_id, source_document_updated_at);
