package crossword2.auth;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료된 refresh 토큰을 정리한다. 폐기된 토큰도 재사용 탐지를 위해 만료 후 {@code refreshTokenRetention}
 * 동안은 남겨 두고, 그 뒤에 삭제한다. 만료 전 토큰(폐기 여부와 무관)은 지우지 않는다.
 */
@Component
public class RefreshTokenCleanup {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanup.class);

	private final RefreshTokenRepository tokens;
	private final AuthProperties props;
	private final Clock clock;

	public RefreshTokenCleanup(RefreshTokenRepository tokens, AuthProperties props, Clock clock) {
		this.tokens = tokens;
		this.props = props;
		this.clock = clock;
	}

	@Scheduled(cron = "0 30 3 * * *")
	public void scheduledPurge() {
		int deleted = purgeExpired();
		if (deleted > 0) {
			log.info("Purged {} expired refresh tokens", deleted);
		}
	}

	/** @return 삭제한 토큰 수 */
	@Transactional
	public int purgeExpired() {
		Instant cutoff = clock.instant().minus(props.refreshTokenRetention());
		return tokens.deleteExpiredBefore(cutoff);
	}
}
