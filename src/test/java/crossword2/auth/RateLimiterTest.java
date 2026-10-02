package crossword2.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import crossword2.common.ApiException;

class RateLimiterTest {

	private static final Duration MINUTE = Duration.ofMinutes(1);

	private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
	private final RateLimiter limiter = new RateLimiter(clock);

	@Test
	void allowsUpToTheLimitThenBlocks() {
		assertThat(limiter.tryAcquire("k", 3, MINUTE)).isTrue();
		assertThat(limiter.tryAcquire("k", 3, MINUTE)).isTrue();
		assertThat(limiter.tryAcquire("k", 3, MINUTE)).isTrue();
		assertThat(limiter.tryAcquire("k", 3, MINUTE)).isFalse();
	}

	@Test
	void windowResetsAfterItElapses() {
		for (int i = 0; i < 3; i++) {
			limiter.tryAcquire("k", 3, MINUTE);
		}
		assertThat(limiter.tryAcquire("k", 3, MINUTE)).isFalse();

		clock.advance(MINUTE);

		assertThat(limiter.tryAcquire("k", 3, MINUTE)).isTrue();
	}

	@Test
	void keysAreIndependent() {
		limiter.tryAcquire("a", 1, MINUTE);

		assertThat(limiter.tryAcquire("a", 1, MINUTE)).isFalse();
		assertThat(limiter.tryAcquire("b", 1, MINUTE)).isTrue();
	}

	@Test
	void zeroLimitBlocksEverything() {
		assertThat(limiter.tryAcquire("k", 0, MINUTE)).isFalse();
	}

	@Test
	void checkThrowsTooManyRequests() {
		limiter.check("k", 1, MINUTE);

		assertThatThrownBy(() -> limiter.check("k", 1, MINUTE)).isInstanceOfSatisfying(ApiException.class, e -> {
			assertThat(e.status().value()).isEqualTo(429);
			assertThat(e.code()).isEqualTo("RATE_LIMITED");
		});
	}
}
