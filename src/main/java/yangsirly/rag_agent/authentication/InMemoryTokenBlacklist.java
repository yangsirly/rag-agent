package yangsirly.rag_agent.authentication;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 JWT jti 黑名单（单实例/测试）。
 * 由 SecurityConfiguration 在 Redis 不可用时装配；
 * 多实例部署必须用 {@link RedisTokenBlacklist}，否则 logout 只影响本节点。
 */
public class InMemoryTokenBlacklist implements TokenBlacklist {

    /** value=过期时间戳（毫秒）。 */
    private final ConcurrentHashMap<String, Long> store = new ConcurrentHashMap<>();

    /**
     * 写入内存黑名单。
     *
     * <p>
     * 并发写由 ConcurrentHashMap 保证；键值覆盖是幂等的。
     * </p>
     */
    @Override
    public void blacklist(String jti, Duration ttl) {
        if (jti == null || jti.isBlank())
            return;
        store.put(jti, System.currentTimeMillis() + ttl.toMillis());
    }

    /**
     * 查询黑名单并顺便惰性清理过期键。
     *
     * <p>
     * 惰性清理可减少后台定时任务复杂度，代价是过期键会在首次访问时才移除。
     * </p>
     */
    @Override
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank())
            return false;
        Long exp = store.get(jti);
        if (exp == null)
            return false;
        if (System.currentTimeMillis() > exp) {
            store.remove(jti);
            return false;
        }
        return true;
    }
}
