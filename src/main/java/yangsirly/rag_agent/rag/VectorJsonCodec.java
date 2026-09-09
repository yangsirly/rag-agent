package yangsirly.rag_agent.rag;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * P2.1 方案 B 的向量 JSON 编解码器。
 *
 * <p>它只是 MySQL JSON baseline 的存储细节，不属于 Embedding 算法本身。
 * 以后切到真正 vector column / Vector DB 时，这一层可以被删除或替换。</p>
 */
@Component
public class VectorJsonCodec {

    private final ObjectMapper objectMapper;

    public VectorJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("vector 不能为空");
        }

        try {
            // 例如 float[]{0.1f, -0.2f} -> "[0.1,-0.2]"
            return objectMapper.writeValueAsString(vector);
        } catch (JsonProcessingException ex) {
            // float[] 正常情况下必然可序列化；失败代表程序/配置异常，不应静默跳过。
            throw new IllegalStateException("vector JSON 序列化失败", ex);
        }
    }

    public float[] decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("embedding JSON 不能为空");
        }

        try {
            return objectMapper.readValue(json, float[].class);
        } catch (JsonProcessingException ex) {
            // 数据库出现损坏向量时应快速失败，不能把错误向量带进相似度排序。
            throw new IllegalStateException("vector JSON 反序列化失败", ex);
        }
    }
}
