package yangsirly.rag_agent.common.exception;

/**
 * 触发限流，对应 429 RATE_LIMITED。
 * 由 RateLimitFilter / AuthService / MessageService 抛出，
 * 异常处理器映射为 429 并携带 Retry-After 与 X-RateLimit-* 头。
 */
public class RateLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;
    /** 触发限流的阈值，用于 X-RateLimit-Limit 响应头；-1 表示未知。 */
    private final int limit;

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        this(message, retryAfterSeconds, -1);
    }

    public RateLimitExceededException(String message, long retryAfterSeconds, int limit) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
        this.limit = limit;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }

    public int getLimit() { return limit; }
}
