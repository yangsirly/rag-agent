package yangsirly.rag_agent.authentication;

import java.time.Duration;

/**
 * JWT jti 黑名单端口。
 *
 * <p>
 * 用途：logout 后将当前 token 的 jti 加入黑名单，在 token 自然过期前拒绝再次使用。
 * </p>
 */
public interface TokenBlacklist {

    /**
     * 将 jti 拉黑到指定 TTL。
     *
     * @param jti token 唯一标识
     * @param ttl 与 token 剩余有效期对齐，避免黑名单 key 永久膨胀
     */
    void blacklist(String jti, Duration ttl);

    /**
     * 查询 jti 是否已在黑名单。
     *
     * @param jti token 唯一标识
     * @return true 表示命中黑名单，应拒绝认证
     */
    boolean isBlacklisted(String jti);
}
