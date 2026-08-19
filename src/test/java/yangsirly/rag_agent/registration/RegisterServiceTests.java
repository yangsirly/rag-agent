package yangsirly.rag_agent.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class RegisterServiceTests {

	private final UserMapper userMapper = mock(UserMapper.class);
	private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
	private final RegisterService registerService = new RegisterService(userMapper, passwordEncoder);

	@Test
	void preparesActiveCustomerWithNormalizedEmailAndHashedPassword() {
		String rawPassword = "password";

		User user = registerService.prepareUser(new RegisterCommand("  User@Example.COM  ", rawPassword));

		assertThat(user.email()).isEqualTo("user@example.com");
		assertThat(user.phone()).isNull();
		// 不只检查哈希字符串发生变化，还要确认它确实能由 BCrypt 校验。
		assertThat(user.passwordHash()).isNotEqualTo(rawPassword);
		assertThat(passwordEncoder.matches(rawPassword, user.passwordHash())).isTrue();
		assertThat(user.role()).isEqualTo(User.Role.CUSTOMER);
		assertThat(user.status()).isEqualTo(User.Status.ACTIVE);
	}

	@Test
	void persistsPreparedUser() {
		String rawPassword = "password";
		when(userMapper.insert(any(UserEntity.class))).thenReturn(1);

		registerService.register(new RegisterCommand(" User@Example.COM ", rawPassword));

		ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
		verify(userMapper).insert(userCaptor.capture());
		UserEntity savedUser = userCaptor.getValue();
		assertThat(savedUser.getEmail()).isEqualTo("user@example.com");
		assertThat(passwordEncoder.matches(rawPassword, savedUser.getPasswordHash())).isTrue();
		assertThat(savedUser.getRole()).isEqualTo(User.Role.CUSTOMER);
		assertThat(savedUser.getStatus()).isEqualTo(User.Status.ACTIVE);
	}

	@Test
	void rejectsUnexpectedInsertCount() {
		when(userMapper.insert(any(UserEntity.class))).thenReturn(0);

		assertThatThrownBy(() -> registerService.register(
				new RegisterCommand("user@example.com", "password")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Registration must insert exactly one user");
	}

	@Test
	void convertsDuplicateEmailToBusinessException() {
		// MyBatis-Spring 会把 JDBC 唯一键错误转换为 DuplicateKeyException。
		when(userMapper.insert(any(UserEntity.class)))
				.thenThrow(new DuplicateKeyException("Constraint uk_users_email violated"));

		assertThatThrownBy(() -> registerService.register(
				new RegisterCommand("user@example.com", "password")))
				.isInstanceOf(EmailAlreadyRegisteredException.class)
				.hasMessage("Email is already registered");
	}

	@Test
	void doesNotMisreportOtherIntegrityFailuresAsDuplicateEmail() {
		DataIntegrityViolationException databaseFailure =
				new DataIntegrityViolationException("Constraint ck_users_status violated");
		when(userMapper.insert(any(UserEntity.class)))
				.thenThrow(databaseFailure);

		assertThatThrownBy(() -> registerService.register(
				new RegisterCommand("user@example.com", "password")))
				.isSameAs(databaseFailure);
	}
}
