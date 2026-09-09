-- V10: 允许同一 Document 保留多个历史 chunk 版本。
--
-- V9 的唯一键 (document_id, chunk_index) 只允许一套 chunk，
-- 与“旧 chunk 延迟回收、通过 source_document_updated_at 判定当前版本”的新策略冲突。
-- 因此把版本时间加入唯一键，并同步调整检索辅助索引。

ALTER TABLE document_chunks
    DROP INDEX uk_document_chunks_document_index,
    DROP INDEX idx_document_chunks_kb_document,
    ADD CONSTRAINT uk_document_chunks_document_version_index
        UNIQUE (document_id, source_document_updated_at, chunk_index),
    ADD KEY idx_document_chunks_kb_document_version
        (knowledge_base_id, document_id, source_document_updated_at);
