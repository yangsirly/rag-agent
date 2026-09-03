package yangsirly.rag_agent.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import yangsirly.rag_agent.registration.User;

class JwtTokenServiceImplTests {

	private static final String SECRET = "test-only-jwt-secret-not-for-production-use-32b";
	private static final String ISSUER = "rag-agent-test";

	private JwtTokenServiceImpl jwtTokenService;
	private AuthenticatedUser sampleUser;

	@BeforeEach
	void setUp() {
		AuthProperties properties = new AuthProperties(
				new AuthProperties.Jwt(SECRET, ISSUER, 1800, 604800),
				new AuthProperties.Cookie("access_token", "refresh_token", false, "Lax", "/"));
		jwtTokenService = new JwtTokenServiceImpl(properties);
		sampleUser = new AuthenticatedUser(42L, "user@example.com", User.Role.CUSTOMER, User.Status.ACTIVE);
	}

	@Test
	void issueAndParseRoundTrip() {
		IssuedAccessToken issued = jwtTokenService.issueAccessToken(sampleUser, "4d5f1f2e-1a4d-4c15-a8c2-313d9f9b7788");

		AuthenticatedUser parsed = jwtTokenService.parseAccessToken(issued.value());

		assertThat(parsed.userId()).isEqualTo(42L);
		assertThat(parsed.email()).isEqualTo("user@example.com");
		assertThat(parsed.role()).isEqualTo(User.Role.CUSTOMER);
		assertThat(parsed.status()).isEqualTo(User.Status.ACTIVE);
		assertThat(jwtTokenService.extractJti(issued.value())).isEqualTo(issued.jti());
		assertThat(jwtTokenService.extractSessionId(issued.value())).isEqualTo(issued.sessionId());
	}

	@Test
	void parseRejectsTamperedToken() {
		String token = jwtTokenService.issueAccessToken(sampleUser);
		String tampered = token.substring(0, token.length() - 4) + "xxxx";

		assertThatThrownBy(() -> jwtTokenService.parseAccessToken(tampered))
				.isInstanceOf(InvalidAccessTokenException.class);
	}

	@Test
	void parseRejectsNonAccessHeaderType() {
		String token = Jwts.builder()
				.header().type("refresh").and()
				.subject("42")
				.issuer(ISSUER)
				.issuedAt(new Date())
				.expiration(new Date(System.currentTimeMillis() + 60_000))
				.claim(JwtTokenServiceImpl.CLAIM_EMAIL, "user@example.com")
				.claim(JwtTokenServiceImpl.CLAIM_ROLE, "CUSTOMER")
				.claim(JwtTokenServiceImpl.CLAIM_STATUS, "ACTIVE")
				.claim(JwtTokenServiceImpl.CLAIM_JTI, "jti")
				.claim(JwtTokenServiceImpl.CLAIM_SESSION_ID, "session")
				.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
				.compact();

		assertThatThrownBy(() -> jwtTokenService.parseAccessToken(token))
				.isInstanceOf(InvalidAccessTokenException.class);
	}

	@Test
	void parseRejectsWrongIssuer() {
		String foreignToken = Jwts.builder()
				.subject("42")
				.issuer("other-issuer")
				.issuedAt(new Date())
				.expiration(new Date(System.currentTimeMillis() + 60_000))
				.claim(JwtTokenServiceImpl.CLAIM_EMAIL, "user@example.com")
				.claim(JwtTokenServiceImpl.CLAIM_ROLE, "CUSTOMER")
				.claim(JwtTokenServiceImpl.CLAIM_STATUS, "ACTIVE")
				.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
				.compact();

		assertThatThrownBy(() -> jwtTokenService.parseAccessToken(foreignToken))
				.isInstanceOf(InvalidAccessTokenException.class);
	}

	@Test
	void parseRejectsExpiredToken() {
		String expiredToken = Jwts.builder()
				.subject("42")
				.issuer(ISSUER)
				.issuedAt(new Date(System.currentTimeMillis() - 120_000))
				.expiration(new Date(System.currentTimeMillis() - 60_000))
				.claim(JwtTokenServiceImpl.CLAIM_EMAIL, "user@example.com")
				.claim(JwtTokenServiceImpl.CLAIM_ROLE, "CUSTOMER")
				.claim(JwtTokenServiceImpl.CLAIM_STATUS, "ACTIVE")
				.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
				.compact();

		assertThatThrownBy(() -> jwtTokenService.parseAccessToken(expiredToken))
				.isInstanceOf(InvalidAccessTokenException.class);
	}

	@Test
	void parseRejectsTokenWithoutExpiration() {
		String tokenWithoutExpiration = Jwts.builder()
				.header().type("access").and()
				.subject("42")
				.issuer(ISSUER)
				.issuedAt(new Date())
				.id("jti")
				.claim(JwtTokenServiceImpl.CLAIM_EMAIL, "user@example.com")
				.claim(JwtTokenServiceImpl.CLAIM_ROLE, "CUSTOMER")
				.claim(JwtTokenServiceImpl.CLAIM_STATUS, "ACTIVE")
				.claim(JwtTokenServiceImpl.CLAIM_SESSION_ID, "session")
				.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
				.compact();

		assertThatThrownBy(() -> jwtTokenService.parseAccessToken(tokenWithoutExpiration))
				.isInstanceOf(InvalidAccessTokenException.class);
	}

	@Test
	void parseRejectsBlankToken() {
		assertThatThrownBy(() -> jwtTokenService.parseAccessToken("  "))
				.isInstanceOf(InvalidAccessTokenException.class);
	}
}
