package crossword2.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import crossword2.common.ApiException;

/** 고정 시간 창 방식의 인메모리 요청 제한. 서버가 여러 대가 되면 Redis로 옮긴다(SPEC 9장). */
@Component
public class RateLimiter {

	private static final int CLEANUP_THRESHOLD = 10_000;

	private record Window(long startMillis, int count) {
	}

	private final Map<String, Window> windows = new ConcurrentHashMap<>();
	private final Clock clock;

	public RateLimiter(Clock clock) {
		this.clock = clock;
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
		if (windows.size() > CLEANUP_THRESHOLD) {
			windows.values().removeIf(w -> now - w.startMillis() >= windowMillis);
		}
		boolean[] allowed = { false };
		windows.compute(key, (k, w) -> {
			if (w == null || now - w.startMillis() >= windowMillis) {
				allowed[0] = limit > 0;
				return new Window(now, allowed[0] ? 1 : 0);
			}
			if (w.count() < limit) {
				allowed[0] = true;
				return new Window(w.startMillis(), w.count() + 1);
			}
			return w;
		});
		return allowed[0];
	}
}
