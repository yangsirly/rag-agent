package yangsirly.rag_agent.rag;

import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 把 Spring AI 的 EmbeddingModel 适配成项目自己的 EmbeddingService。
 *
 * <p>Spring AI 负责 provider 协议和 HTTP 客户端；本类只处理我们项目关心的契约：
 * 批量输入顺序、空向量保护和模型名记录。</p>
 */
@Service
public class SpringAiEmbeddingService implements EmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final String modelName;

    public SpringAiEmbeddingService(
            EmbeddingModel embeddingModel,
            @Value("${spring.ai.ollama.embedding.model}") String modelName) {
        this.embeddingModel = embeddingModel;
        this.modelName = modelName;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("embedding 输入不能为空");
        }
        if (texts.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new IllegalArgumentException("embedding 文本不能为空");
        }

        // Spring AI 的批量 API 一次把多个 chunk 交给 Ollama，
        // 比在 for 循环里逐条发 HTTP 请求更符合后续性能需求。
        List<float[]> vectors = embeddingModel.embed(texts);

        // provider 若少返回/多返回向量，绝不能继续按下标错误绑定 chunk。
        if (vectors.size() != texts.size()) {
            throw new IllegalStateException("embedding 返回数量与输入数量不一致");
        }

        // 空向量没有任何检索意义，也会让 cosine 分母为 0。
        if (vectors.stream().anyMatch(vector -> vector == null || vector.length == 0)) {
            throw new IllegalStateException("embedding 返回空向量");
        }

        return vectors;
    }

    @Override
    public String modelName() {
        return modelName;
    }
}
