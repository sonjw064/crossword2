package crossword2.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class RefreshTokenCleanupTest {

	private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

	@Autowired
	RefreshTokenRepository tokens;

	@Autowired
	AuthProperties props;

	private String save(Duration expiresRelativeToNow, boolean revoked) {
		String hash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
		RefreshToken token = new RefreshToken(hash, UUID.randomUUID(), new Owner(OwnerType.GUEST, UUID.randomUUID().toString()),
				NOW.minus(Duration.ofDays(120)), NOW.plus(expiresRelativeToNow));
		if (revoked) {
			token.revoke(NOW.minus(Duration.ofDays(100)));
		}
		tokens.save(token);
		return hash;
	}

	@Test
	@Transactional // 직접 만든 인스턴스는 트랜잭션 프록시가 없으므로 테스트 트랜잭션에서 실행(끝나면 롤백)
	void removesTokensExpiredLongAgoButKeepsRecentAndLiveOnes() {
		RefreshTokenCleanup cleanup = new RefreshTokenCleanup(tokens, props, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
		String longExpired = save(Duration.ofDays(-30), false);
		String longExpiredRevoked = save(Duration.ofDays(-10), true);
		String recentlyExpired = save(Duration.ofDays(-2), true); // 보존 기간(7일) 안: 재사용 탐지를 위해 유지
		String stillValid = save(Duration.ofDays(20), false);
		String revokedButNotExpired = save(Duration.ofDays(20), true); // 만료 전 폐기 토큰도 유지

		int deleted = cleanup.purgeExpired();

		assertThat(deleted).isGreaterThanOrEqualTo(2);
		assertThat(tokens.findByTokenHash(longExpired)).isEmpty();
		assertThat(tokens.findByTokenHash(longExpiredRevoked)).isEmpty();
		assertThat(tokens.findByTokenHash(recentlyExpired)).isPresent();
		assertThat(tokens.findByTokenHash(stillValid)).isPresent();
		assertThat(tokens.findByTokenHash(revokedButNotExpired)).isPresent();
	}

	@Test
	void retentionDefaultsToSevenDays() {
		assertThat(props.refreshTokenRetention()).isEqualTo(Duration.ofDays(7));
	}
}
