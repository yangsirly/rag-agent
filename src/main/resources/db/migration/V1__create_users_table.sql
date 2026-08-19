CREATE TABLE users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户主键',

    email VARCHAR(254) NULL COMMENT '规范化后的邮箱',
    phone VARCHAR(32) NULL COMMENT '规范化后的手机号',

    password_hash VARCHAR(255) NOT NULL COMMENT 'BCrypt 密码哈希',

    role VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER'
        COMMENT '用户角色：CUSTOMER 或 EDITOR',

    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        COMMENT '用户状态：ACTIVE 或 DISABLED',

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_phone UNIQUE (phone),

    CONSTRAINT ck_users_contact
        CHECK (email IS NOT NULL OR phone IS NOT NULL),

    CONSTRAINT ck_users_role
        CHECK (role IN ('CUSTOMER', 'EDITOR')),

    CONSTRAINT ck_users_status
        CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户表';