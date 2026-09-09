-- V7: 创建知识库表
-- 知识库归属创建者（EDITOR），同一创建者下名称唯一。
-- 预留 editor_ids 与 reader_ids 供后续多用户授权使用。

CREATE TABLE knowledge_bases (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '知识库主键',
    creator_id BIGINT UNSIGNED NOT NULL COMMENT '创建者 ID，关联 users.id',
    name VARCHAR(100) NOT NULL COMMENT '知识库名称',
    description VARCHAR(1000) NULL COMMENT '知识库描述',

    editor_ids JSON NULL COMMENT '拥有编辑权限的用户 ID 列表（预留）',
    reader_ids JSON NULL COMMENT '拥有阅读权限的用户 ID 列表（预留）',

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL COMMENT '软删除时间戳，NULL=有效',

    PRIMARY KEY (id),

    -- 同一创建者下知识库名称唯一
    CONSTRAINT uk_knowledge_bases_creator_name UNIQUE (creator_id, name),

    -- 列表查询复合索引
    KEY idx_knowledge_bases_creator_updated (creator_id, deleted_at, updated_at),

    -- 外键约束
    CONSTRAINT fk_knowledge_bases_creator FOREIGN KEY (creator_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '知识库表';
