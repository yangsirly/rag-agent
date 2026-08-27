package yangsirly.rag_agent.registration;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * users 表的 MyBatis-Plus 映射。
 *
 * <p>
 * 该类只处理数据库映射；注册规则仍放在 RegisterService 中。
 * 业务对象与表映射分离，避免 {@code User} record 依赖持久化框架注解。
 * </p>
 * <p>
 * 工业级新增：failedLoginCount / lockUntil 用于登录防刷兜底（Redis 为主，DB 为备）。
 * </p>
 */
@TableName("users")
public class UserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String email;

    private String phone;

    @TableField("password_hash")
    private String passwordHash;

    private User.Role role;

    private User.Status status;

    @TableField("failed_login_count")
    private Integer failedLoginCount;

    @TableField("lock_until")
    private LocalDateTime lockUntil;

    /** MyBatis 查询结果映射时需要无参构造器。 */
    protected UserEntity() {
    }

    private UserEntity(String email, String phone, String passwordHash, User.Role role, User.Status status) {
        this.email = email;
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = status;
        this.failedLoginCount = 0;
    }

    /** 在持久化边界把业务数据转换为 MyBatis-Plus 表映射对象。 */
    public static UserEntity from(User user) {
        return new UserEntity(
                user.email(),
                user.phone(),
                user.passwordHash(),
                user.role(),
                user.status());
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public User.Role getRole() {
        return role;
    }

    public User.Status getStatus() {
        return status;
    }

    public Integer getFailedLoginCount() {
        return failedLoginCount;
    }

    /** 更新失败登录计数（防刷兜底）。 */
    public void setFailedLoginCount(Integer failedLoginCount) {
        this.failedLoginCount = failedLoginCount;
    }

    public LocalDateTime getLockUntil() {
        return lockUntil;
    }

    /** 设置账号锁定截止时间。 */
    public void setLockUntil(LocalDateTime lockUntil) {
        this.lockUntil = lockUntil;
    }
}
