package yangsirly.rag_agent.dev;

import java.util.Arrays;
import java.util.Locale;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import yangsirly.rag_agent.registration.User;
import yangsirly.rag_agent.registration.UserEntity;
import yangsirly.rag_agent.registration.UserMapper;

/** 本地开发身份入口：只插入新 EDITOR，不提升已有用户权限或重置密码。 */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "app.dev.editor-seed", name = "enabled", havingValue = "true")
public class DevEditorSeeder implements ApplicationRunner {
    private final UserMapper users;
    private final PasswordEncoder encoder;
    private final ConfigurableEnvironment environment;

    public DevEditorSeeder(UserMapper users, PasswordEncoder encoder, ConfigurableEnvironment environment) {
        this.users = users;
        this.encoder = encoder;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 仅允许 local 单独激活，避免 local 与生产配置混用。
        if (!Arrays.equals(environment.getActiveProfiles(), new String[] {"local"})) {
            throw new IllegalStateException("Dev editor seed requires local as the only active profile");
        }
        // 环境变量是唯一凭据来源，不读取配置文件中的同名属性，也不打印配置值。
        String email = environment.getSystemEnvironment().get("APP_DEV_EDITOR_SEED_EMAIL") instanceof String value ? value : null;
        String password = environment.getSystemEnvironment().get("APP_DEV_EDITOR_SEED_PASSWORD") instanceof String value ? value : null;
        if (email == null || password == null) {
            throw new IllegalStateException("Dev editor seed requires email and password environment variables");
        }
        email = email.strip().toLowerCase(Locale.ROOT);
        int length = password.codePointCount(0, password.length());
        if (email.length() > 254 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
                || length < 8 || length > 64) {
            throw new IllegalStateException("Invalid dev editor seed email or password");
        }
        UserEntity existing = users.findByEmail(email);
        if (existing != null) {
            if (existing.getRole() != User.Role.EDITOR || existing.getStatus() != User.Status.ACTIVE) {
                throw new IllegalStateException("Dev editor seed requires an ACTIVE EDITOR; existing user is not modified");
            }
            if (!encoder.matches(password, existing.getPasswordHash())) {
                throw new IllegalStateException("Dev editor seed password mismatch; existing password is not modified");
            }
            return;
        }
        // 数据库邮箱唯一约束处理并发碰撞；失败回滚后可重启验证已有身份。
        if (users.insert(UserEntity.from(new User(email, null, encoder.encode(password),
                User.Role.EDITOR, User.Status.ACTIVE))) != 1) {
            throw new IllegalStateException("Dev editor seed must insert exactly one user");
        }
    }
}
