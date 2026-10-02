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
	void cleanupOnlyRemovesEntriesWhoseOwnWindowHasEnded() {
		RateLimiter small = new RateLimiter(clock, 5);
		Duration hour = Duration.ofHours(1);
		small.tryAcquire("signup:1.2.3.4", 1, hour); // 1시간 창, 한도 소진
		for (int i = 0; i < 10; i++) {
			small.tryAcquire("login:" + i, 5, MINUTE); // 1분 창 항목으로 cleanup 기준 크기를 넘긴다
		}
		clock.advance(Duration.ofMinutes(2)); // 1분 창은 끝났고 1시간 창은 아직 유효

		small.tryAcquire("login:trigger", 5, MINUTE); // 1분 창 요청이 cleanup을 일으킨다

		assertThat(small.tryAcquire("signup:1.2.3.4", 1, hour)).as("1시간 제한이 초기화되면 안 된다").isFalse();
		assertThat(small.size()).as("끝난 1분 창 항목은 정리된다").isLessThan(5);
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
