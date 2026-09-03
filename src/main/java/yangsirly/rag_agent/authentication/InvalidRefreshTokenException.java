package yangsirly.rag_agent.authentication;

/** Refresh Token 缺失、伪造、过期、撤销或重放时的内部异常。 */
public class InvalidRefreshTokenException extends RuntimeException {

    public enum Reason {
        INVALID,
        EXPIRED,
        REUSED,
        DISABLED
    }

    private final Reason reason;

    public InvalidRefreshTokenException(Reason reason) {
        super("Refresh token is invalid");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
