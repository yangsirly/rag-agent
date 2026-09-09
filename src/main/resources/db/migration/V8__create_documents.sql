-- V8: 创建知识库文档表
-- 文档依附于知识库，同知识库下标题唯一。
-- 包含正文内容与字数字段，支持软删除与级联标记。

CREATE TABLE documents (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '文档主键',
    knowledge_base_id BIGINT UNSIGNED NOT NULL COMMENT '所属知识库 ID',
    creator_id BIGINT UNSIGNED NOT NULL COMMENT '文档创建者 ID',
    title VARCHAR(100) NOT NULL COMMENT '文档标题',
    summary VARCHAR(500) NULL COMMENT '文档摘要',
    content TEXT NOT NULL COMMENT '正文内容',
    content_length INT NOT NULL DEFAULT 0 COMMENT '正文字数',

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL COMMENT '软删除时间戳，NULL=有效',

    PRIMARY KEY (id),

    -- 同库下标题唯一约束
    CONSTRAINT uk_documents_kb_title UNIQUE (knowledge_base_id, title),

    -- 加速列表查询与时间倒序排序
    KEY idx_documents_kb_updated (knowledge_base_id, deleted_at, updated_at),

    -- 外键关联
    CONSTRAINT fk_documents_knowledge_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases (id) ON DELETE RESTRICT,
    CONSTRAINT fk_documents_creator FOREIGN KEY (creator_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '知识库文档表';
