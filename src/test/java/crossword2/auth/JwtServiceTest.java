package crossword2.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtServiceTest {

	private static final SecretKey KEY = new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(), "HmacSHA256");
	private static final AuthProperties PROPS = new AuthProperties(null, Duration.ofMinutes(30),
			Duration.ofDays(30), Duration.ofDays(90), Duration.ofDays(7));

	private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
	private final JwtService service = new JwtService(KEY, clock, PROPS);
	private final JwtDecoder decoder = AuthConfig.buildDecoder(KEY, clock);

	private static Account guest() {
		return new Account(new Owner(OwnerType.GUEST, UUID.randomUUID().toString()), "손님", null, null);
	}

	private static Account member(Role role) {
		return new Account(new Owner(OwnerType.MEMBER, "7"), "회원", "a@b.com", role);
	}

	@Test
	void guestTokenCarriesIdentityButNoRole() {
		Account account = guest();

		Jwt jwt = decoder.decode(service.issue(account).value());

		assertThat(jwt.getSubject()).isEqualTo(account.owner().id());
		assertThat(jwt.getClaimAsString("ownerType")).isEqualTo("GUEST");
		assertThat(jwt.getClaimAsString("nickname")).isEqualTo("손님");
		assertThat(jwt.getClaimAsString("role")).isNull();
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("crossword2");
	}

	@Test
	void memberTokenCarriesRole() {
		Jwt jwt = decoder.decode(service.issue(member(Role.ADMIN)).value());

		assertThat(Owner.fromJwt(jwt)).isEqualTo(new Owner(OwnerType.MEMBER, "7"));
		assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
	}

	@Test
	void reportsTtlInSeconds() {
		assertThat(service.issue(guest()).expiresInSeconds()).isEqualTo(1800);
	}

	@Test
	void tokenIsValidUntilItExpires() {
		String token = service.issue(guest()).value();

		clock.advance(Duration.ofMinutes(29).plusSeconds(59));
		assertThat(decoder.decode(token)).isNotNull();

		clock.advance(Duration.ofSeconds(2));
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	void rejectsTamperedPayload() {
		String token = service.issue(guest()).value();
		String[] parts = token.split("\\.");
		String forged = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA" + "." + parts[2];

		assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
	}

	@Test
	void rejectsTokenSignedWithAnotherKey() {
		SecretKey other = new SecretKeySpec("ffffffffffffffffffffffffffffffff".getBytes(), "HmacSHA256");
		String token = new JwtService(other, clock, PROPS).issue(guest()).value();

		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	void rejectsGarbage() {
		assertThatThrownBy(() -> decoder.decode("not.a.jwt")).isInstanceOf(JwtException.class);
		assertThatThrownBy(() -> decoder.decode("")).isInstanceOf(JwtException.class);
	}
}
