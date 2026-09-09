-- V9: 创建 RAG 文档分块表。
-- document_chunks 是从 documents.content 派生出的内部检索数据，不是用户直接管理的业务文档。
-- P2.1 baseline 先把 embedding 以 JSON 持久化到 MySQL，由 Java 做 Exact Search；
-- 后续若切换专业向量库，可保留本表作为 chunk/source metadata 的事实来源。

-- knowledge_base_id 会直接参与 Retrieval 权限过滤，因此数据库必须保证：
-- chunk.knowledge_base_id 与 chunk.document_id 对应文档的 knowledge_base_id 一致。
-- id 本身已是主键，这个复合唯一键不是为“再保证一次唯一”，而是为下面的复合外键提供数据库级资源绑定约束。
ALTER TABLE documents
    ADD CONSTRAINT uk_documents_kb_id UNIQUE (knowledge_base_id, id);

CREATE TABLE document_chunks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '分块主键',
    knowledge_base_id BIGINT UNSIGNED NOT NULL COMMENT '冗余保存所属知识库，便于检索时直接做可信范围过滤',
    document_id BIGINT UNSIGNED NOT NULL COMMENT '来源文档 ID',
    chunk_index INT UNSIGNED NOT NULL COMMENT '文档内分块顺序，从 0 开始',
    content TEXT NOT NULL COMMENT '分块正文',

    -- offset 与 documents.content_length 使用同一口径：Unicode code point。
    -- start_offset 为 0-based inclusive，end_offset 为 exclusive。
    start_offset INT UNSIGNED NOT NULL COMMENT '原文起始 code point offset（含）',
    end_offset INT UNSIGNED NOT NULL COMMENT '原文结束 code point offset（不含）',

    -- 方案 B baseline：MySQL 只负责持久化，Java 读取指定 KB 的向量后计算 cosine similarity。
    -- 在真正选定 embedding 模型前允许为空；完成 embedding 后三列必须同时有值。
    embedding JSON NULL COMMENT 'embedding 向量 JSON 数组；P2.1 exact-search baseline 使用',
    embedding_model VARCHAR(100) NULL COMMENT '生成该向量的 embedding 模型标识',
    embedding_dimension INT UNSIGNED NULL COMMENT '向量维度',

    -- 记录生成 chunk 时对应的文档版本，便于发现“文档已更新但 chunk 仍旧”的陈旧索引。
    source_document_updated_at DATETIME(6) NOT NULL COMMENT '生成分块时 documents.updated_at 的值',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    -- 同一文档一次有效索引中，每个 chunk_index 只能出现一次。
    CONSTRAINT uk_document_chunks_document_index UNIQUE (document_id, chunk_index),

    -- Retrieval 第一层必须先限定 knowledge_base_id，再读取该库下候选 chunks。
    KEY idx_document_chunks_kb_document (knowledge_base_id, document_id),

    -- 复合外键同时约束“文档存在”和“该文档确实属于声明的知识库”。
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
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'RAG 文档分块与 embedding baseline 存储';
