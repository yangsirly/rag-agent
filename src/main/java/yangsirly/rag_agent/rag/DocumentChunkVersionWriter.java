package yangsirly.rag_agent.rag;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 只负责把“一整个 Document 版本”的 chunks 原子写入数据库。
 *
 * <p>为什么单独拆这个类：Embedding 是 HTTP/模型计算，可能很慢；
 * 我们先在事务外把向量全部算完，最后只把 INSERT 包进短事务。
 * 这样既避免长事务，也避免只写入半个版本。</p>
 */
@Service
public class DocumentChunkVersionWriter {

    private final DocumentChunkMapper documentChunkMapper;

    public DocumentChunkVersionWriter(DocumentChunkMapper documentChunkMapper) {
        this.documentChunkMapper = documentChunkMapper;
    }

    /**
     * 同一个版本的所有 chunks 要么全部写成功，要么全部回滚。
     */
    @Transactional
    public void insertVersion(List<DocumentChunkEntity> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("待写入 chunk 不能为空");
        }

        for (DocumentChunkEntity chunk : chunks) {
            documentChunkMapper.insert(chunk);
        }
    }
}
