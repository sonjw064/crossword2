package crossword2.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AuthConfigTest {

	private final AuthConfig config = new AuthConfig();

	private static AuthProperties props(String secret) {
		return new AuthProperties(secret, Duration.ofMinutes(30), Duration.ofDays(30), Duration.ofDays(90));
	}

	@Test
	void prodRefusesToStartWithoutASecret() {
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");

		assertThatThrownBy(() -> config.jwtSecretKey(props(null), prod)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("JWT_SECRET");
		assertThatThrownBy(() -> config.jwtSecretKey(props("  "), prod)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void rejectsASecretThatIsTooShort() {
		assertThatThrownBy(() -> config.jwtSecretKey(props("too-short"), new MockEnvironment()))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void devGeneratesADifferentRandomKeyEachTime() {
		SecretKey a = config.jwtSecretKey(props(""), new MockEnvironment());
		SecretKey b = config.jwtSecretKey(props(""), new MockEnvironment());

		assertThat(a.getEncoded()).hasSize(32).isNotEqualTo(b.getEncoded());
	}

	@Test
	void usesTheConfiguredSecret() {
		String secret = "0123456789abcdef0123456789abcdef";

		SecretKey key = config.jwtSecretKey(props(secret), new MockEnvironment());

		assertThat(key.getEncoded()).isEqualTo(secret.getBytes());
		assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
	}
}
