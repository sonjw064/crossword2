package crossword2.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
		@DefaultValue("10") int loginPerMinute,
		@DefaultValue("10") int signupPerHour,
		@DefaultValue("30") int guestPerHour) {
}
