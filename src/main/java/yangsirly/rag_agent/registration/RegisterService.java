package yangsirly.rag_agent.registration;

import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册业务逻辑的边界。
 *
 * <p>
 * {@code @Service} 会把该类注册为 Spring Bean，Controller 可以通过构造器注入它。
 * Controller 只处理 HTTP 输入输出，真正的注册规则、事务和持久化应放在这里。
 * </p>
 */
@Service
public class RegisterService {
	private static final int MAX_EMAIL_LENGTH = 254;
	private static final int MIN_PASSWORD_LENGTH = 8;
	private static final int MAX_PASSWORD_LENGTH = 64;
	private static final String EMAIL_UNIQUE_CONSTRAINT = "uk_users_email";
	private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
	private final UserMapper userMapper;
	private final PasswordEncoder passwordEncoder;

	public RegisterService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
		this.userMapper = userMapper;
		this.passwordEncoder = passwordEncoder;
	}

	/**
	 * 注册的事务边界：用户数据要么完整写入，要么回滚。
	 *
	 * <p>不先查询邮箱是否存在，因为“先查后写”无法阻止并发请求同时通过检查。
	 * 真正的唯一性由数据库 {@code uk_users_email} 约束保证。</p>
	 */
	@Transactional
	public void register(RegisterCommand command) {
		User user = prepareUser(command);
		try {
			// MyBatis 在 insert 调用点立即执行 SQL，没有 JPA 延迟 flush 的实体状态。
			int insertedRows = userMapper.insert(UserEntity.from(user));
			if (insertedRows != 1) {
				throw new IllegalStateException("Registration must insert exactly one user");
			}
		}
		catch (DuplicateKeyException exception) {
			if (containsConstraintName(exception, EMAIL_UNIQUE_CONSTRAINT)) {
				throw new EmailAlreadyRegisteredException(exception);
			}
			// 其他唯一键错误不能伪装成邮箱重复。
			throw exception;
		}
	}

	private boolean containsConstraintName(Throwable exception, String constraintName) {
		String normalizedConstraintName = constraintName.toLowerCase(Locale.ROOT);
		Throwable current = exception;
		while (current != null) {
			// MySQL 和 H2 驱动都会在异常链中包含违反的约束名。
			String message = current.getMessage();
			if (message != null && message.toLowerCase(Locale.ROOT).contains(normalizedConstraintName)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	/**
	 * 将外部注册命令转换为可持久化的用户数据。
	 *
	 * <p>明文密码只在这个方法内用于生成 BCrypt 哈希，不会放入返回的 User。</p>
	 */
	User prepareUser(RegisterCommand command) {
		if (command == null) {
			throw new IllegalArgumentException("Register command must not be null");
		}

		String email = command.email();
		String password = command.password();
		if (email == null || password == null) {
			throw new IllegalArgumentException("Email and password must not be null");
		}

		String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
		if (normalizedEmail.length() > MAX_EMAIL_LENGTH
				|| !EMAIL_PATTERN.matcher(normalizedEmail).matches()) {
			throw new IllegalArgumentException("Invalid email format");
		}

		int passwordLength = password.codePointCount(0, password.length());
		if (passwordLength < MIN_PASSWORD_LENGTH || passwordLength > MAX_PASSWORD_LENGTH) {
			throw new IllegalArgumentException("Password must contain between 8 and 64 characters");
		}

		String passwordHash = passwordEncoder.encode(password);
		return new User(
				normalizedEmail,
				null,
				passwordHash,
				User.Role.CUSTOMER,
				User.Status.ACTIVE);
	}
}
