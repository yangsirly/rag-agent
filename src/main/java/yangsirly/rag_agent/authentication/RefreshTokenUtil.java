package yangsirly.rag_agent.authentication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/** Refresh Token 的生成、解析和哈希工具，不保存任何明文凭证。 */
final class RefreshTokenUtil {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private RefreshTokenUtil() {
    }

    static Generated generate() {
        String sessionId = UUID.randomUUID().toString();
        return generateForSession(sessionId);
    }

    static Generated generateForSession(String sessionId) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return new Generated(sessionId, sessionId + "." + BASE64_URL.encodeToString(secret));
    }

    static String sessionId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        int separator = token.indexOf('.');
        if (separator != 36 || token.indexOf('.', separator + 1) >= 0) {
            return null;
        }
        String candidate = token.substring(0, separator);
        String secretPart = token.substring(separator + 1);
        if (secretPart.isBlank()) {
            return null;
        }
        try {
            byte[] secret = Base64.getUrlDecoder().decode(secretPart);
            return UUID.fromString(candidate).toString().equals(candidate) && secret.length == 32
                    ? candidate
                    : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    static byte[] hash(String token) {
        if (token == null) {
            return null;
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JRE", exception);
        }
    }

    static boolean matches(String token, byte[] expectedHash) {
        byte[] actual = hash(token);
        return actual != null && expectedHash != null && MessageDigest.isEqual(actual, expectedHash);
    }

    record Generated(String sessionId, String token) {
    }
}
