package yangsirly.rag_agent.rag;

import java.util.List;

/**
 * RAG 业务层自己的 Embedding 边界。
 *
 * <p>为什么不让业务代码直接依赖 Ollama/OpenAI：
 * 模型供应方是一个真实会变化的外部边界。只要这里保持稳定，
 * 后续切换 provider 时 DocumentIndexService / ExactRetriever 不需要重写。</p>
 */
public interface EmbeddingService {

    /**
     * 批量把文本转换成向量。
     *
     * <p>返回顺序必须与输入顺序完全一致：第 i 个文本对应第 i 个向量。
     * 这是后面把 chunk 与 embedding 一一绑定的核心不变量。</p>
     */
    List<float[]> embedAll(List<String> texts);

    /** 单条 Query Embedding 只是批量接口的便捷形式。 */
    default float[] embed(String text) {
        return embedAll(List.of(text)).getFirst();
    }

    /** 当前真实模型标识，会持久化到 document_chunks.embedding_model。 */
    String modelName();
}
