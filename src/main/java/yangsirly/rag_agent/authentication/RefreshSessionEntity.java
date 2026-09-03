package yangsirly.rag_agent.authentication;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** refresh_sessions 表映射；明文 Refresh Token 永远不进入实体。 */
@TableName("refresh_sessions")
public class RefreshSessionEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    @TableField("user_id")
    private Long userId;

    @TableField("token_hash")
    private byte[] tokenHash;

    @TableField("current_access_jti")
    private String currentAccessJti;

    @TableField("current_access_expires_at")
    private LocalDateTime currentAccessExpiresAt;

    @TableField("expires_at")
    private LocalDateTime expiresAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("last_used_at")
    private LocalDateTime lastUsedAt;

    @TableField("revoked_at")
    private LocalDateTime revokedAt;

    protected RefreshSessionEntity() {
    }

    public RefreshSessionEntity(String id, Long userId, byte[] tokenHash, String currentAccessJti,
            LocalDateTime currentAccessExpiresAt, LocalDateTime expiresAt, LocalDateTime createdAt,
            LocalDateTime lastUsedAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.currentAccessJti = currentAccessJti;
        this.currentAccessExpiresAt = currentAccessExpiresAt;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
    }

    public String getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public byte[] getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(byte[] tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getCurrentAccessJti() {
        return currentAccessJti;
    }

    public void setCurrentAccessJti(String currentAccessJti) {
        this.currentAccessJti = currentAccessJti;
    }

    public LocalDateTime getCurrentAccessExpiresAt() {
        return currentAccessExpiresAt;
    }

    public void setCurrentAccessExpiresAt(LocalDateTime currentAccessExpiresAt) {
        this.currentAccessExpiresAt = currentAccessExpiresAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(LocalDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }
}
