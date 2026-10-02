package crossword2.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code jwtSecret}은 환경변수 JWT_SECRET로 받는다. 개발 환경에서 비어 있으면 실행마다 임의 키를 만든다. */
@ConfigurationProperties("app.auth")
public record AuthProperties(
		String jwtSecret,
		@DefaultValue("30m") Duration accessTokenTtl,
		@DefaultValue("30d") Duration memberRefreshTtl,
		@DefaultValue("90d") Duration guestRefreshTtl,
		@DefaultValue("7d") Duration refreshTokenRetention) {
}
