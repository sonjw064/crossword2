package crossword2.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import crossword2.common.ApiException;

/** 고정 시간 창 방식의 인메모리 요청 제한. 서버가 여러 대가 되면 Redis로 옮긴다(SPEC 9장). */
@Component
public class RateLimiter {

	private static final int DEFAULT_CLEANUP_THRESHOLD = 10_000;

	/** 시간 창마다 길이가 다르므로(1분, 1시간 등) 각 항목이 자신의 종료 시각을 가진다. */
	private record Window(long endMillis, int count) {
	}

	private final Map<String, Window> windows = new ConcurrentHashMap<>();
	private final Clock clock;
	private final int cleanupThreshold;

	@Autowired
	public RateLimiter(Clock clock) {
		this(clock, DEFAULT_CLEANUP_THRESHOLD);
	}

	RateLimiter(Clock clock, int cleanupThreshold) {
		this.clock = clock;
		this.cleanupThreshold = cleanupThreshold;
	}

	/** 한도를 넘으면 429(RATE_LIMITED)를 던진다. */
	public void check(String key, int limit, Duration window) {
		if (!tryAcquire(key, limit, window)) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
					"too many requests, please try again later");
		}
	}

	boolean tryAcquire(String key, int limit, Duration window) {
		long now = clock.millis();
		long windowMillis = window.toMillis();
		if (windows.size() > cleanupThreshold) {
			windows.values().removeIf(w -> now >= w.endMillis());
		}
		boolean[] allowed = { false };
		windows.compute(key, (k, w) -> {
			if (w == null || now >= w.endMillis()) {
				allowed[0] = limit > 0;
				return new Window(now + windowMillis, allowed[0] ? 1 : 0);
			}
			if (w.count() < limit) {
				allowed[0] = true;
				return new Window(w.endMillis(), w.count() + 1);
			}
			return w;
		});
		return allowed[0];
	}

	int size() {
		return windows.size();
	}
}
