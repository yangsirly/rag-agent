package yangsirly.rag_agent.registration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 注册流程使用的安全组件配置。 */
@Configuration
public class RegistrationConfiguration {

	/**
	 * 把密码编码器作为 Bean 集中管理，便于调整 BCrypt 成本或未来迁移算法。
	 */
	@Bean
	public PasswordEncoder passwordEncoder(
			@Value("${security.password.bcrypt-strength:12}") int strength) {
		return new BCryptPasswordEncoder(strength);
	}
}
