-- Refresh 会话只保存当前 Token 的哈希，不保存可直接使用的明文凭证。
CREATE TABLE refresh_sessions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '应用生成的会话 UUID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '所属用户',
    token_hash BINARY(32) NOT NULL COMMENT '当前 Refresh Token 的 SHA-256 哈希',
    current_access_jti CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前 Access Token 的 jti',
    current_access_expires_at DATETIME(6) NULL COMMENT '当前 Access Token 过期时间',
    expires_at DATETIME(6) NOT NULL COMMENT '会话绝对过期时间',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_used_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    revoked_at DATETIME(6) NULL COMMENT '会话撤销时间',

    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    INDEX idx_refresh_sessions_user_expires (user_id, expires_at),
    INDEX idx_refresh_sessions_expires (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '一次性 Refresh Token 会话';
