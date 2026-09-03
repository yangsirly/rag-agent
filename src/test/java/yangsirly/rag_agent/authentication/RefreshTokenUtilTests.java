package yangsirly.rag_agent.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RefreshTokenUtilTests {

	@Test
	void generatesParseableHighEntropyTokenBoundToSession() {
		RefreshTokenUtil.Generated generated = RefreshTokenUtil.generate();

		assertThat(UUID.fromString(generated.sessionId()).toString()).isEqualTo(generated.sessionId());
		assertThat(generated.token()).startsWith(generated.sessionId() + ".");
		assertThat(RefreshTokenUtil.sessionId(generated.token())).isEqualTo(generated.sessionId());
		assertThat(RefreshTokenUtil.matches(generated.token(), RefreshTokenUtil.hash(generated.token()))).isTrue();
	}

	@Test
	void randomSecretChangesOnEveryGenerationEvenForSameSession() {
		String sessionId = UUID.randomUUID().toString();
		RefreshTokenUtil.Generated first = RefreshTokenUtil.generateForSession(sessionId);
		RefreshTokenUtil.Generated second = RefreshTokenUtil.generateForSession(sessionId);

		assertThat(second.token()).isNotEqualTo(first.token());
		assertThat(RefreshTokenUtil.hash(second.token())).hasSize(32);
		assertThat(Arrays.equals(RefreshTokenUtil.hash(first.token()), RefreshTokenUtil.hash(second.token())))
				.isFalse();
	}

	@Test
	void rejectsMalformedOrTamperedRefreshToken() {
		RefreshTokenUtil.Generated generated = RefreshTokenUtil.generate();
		int secretStart = generated.token().indexOf('.') + 1;
		char replacement = generated.token().charAt(secretStart) == 'A' ? 'B' : 'A';
		String tampered = generated.token().substring(0, secretStart) + replacement
				+ generated.token().substring(secretStart + 1);

		assertThat(RefreshTokenUtil.sessionId(null)).isNull();
		assertThat(RefreshTokenUtil.sessionId(" ")).isNull();
		assertThat(RefreshTokenUtil.sessionId("not-a-session.secret")).isNull();
		assertThat(RefreshTokenUtil.sessionId(generated.sessionId() + ".bad.secret")).isNull();
		// 篡改后的值仍可能满足格式，因此必须依靠数据库哈希比较拒绝它。
		assertThat(RefreshTokenUtil.sessionId(tampered)).isEqualTo(generated.sessionId());
		assertThat(RefreshTokenUtil.matches(tampered, RefreshTokenUtil.hash(generated.token()))).isFalse();
	}
}
