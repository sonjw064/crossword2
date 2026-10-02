package crossword2.auth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

@Configuration
@EnableConfigurationProperties({ AuthProperties.class, RateLimitProperties.class })
public class AuthConfig {

	static final String ISSUER = "crossword2";
	private static final int MIN_SECRET_BYTES = 32;

	/** prod에서는 JWT_SECRET 필수. 그 외에는 비어 있으면 실행마다 임의 키를 만든다(재시작하면 기존 토큰 무효). */
	@Bean
	SecretKey jwtSecretKey(AuthProperties props, Environment env) {
		byte[] bytes;
		if (props.jwtSecret() == null || props.jwtSecret().isBlank()) {
			if (env.acceptsProfiles(Profiles.of("prod"))) {
				throw new IllegalStateException("JWT_SECRET must be set when the prod profile is active");
			}
			bytes = new byte[MIN_SECRET_BYTES];
			new SecureRandom().nextBytes(bytes);
		} else {
			bytes = props.jwtSecret().getBytes(StandardCharsets.UTF_8);
			if (bytes.length < MIN_SECRET_BYTES) {
				throw new IllegalStateException("JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes");
			}
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSecretKey, Clock clock) {
		return buildDecoder(jwtSecretKey, clock);
	}

	/** 만료 판단에 주입된 Clock을 쓰도록 검증기를 구성한다(테스트에서 시간을 움직일 수 있다). */
	static JwtDecoder buildDecoder(SecretKey key, Clock clock) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
		timestamps.setClock(clock);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(ISSUER)));
		return decoder;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(jwt -> {
			List<GrantedAuthority> authorities = new ArrayList<>();
			if (OwnerType.GUEST.name().equals(jwt.getClaimAsString(Owner.CLAIM_OWNER_TYPE))) {
				authorities.add(new SimpleGrantedAuthority("ROLE_GUEST"));
			} else {
				authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
				if (Role.ADMIN.name().equals(jwt.getClaimAsString("role"))) {
					authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
				}
			}
			return authorities;
		});
		return converter;
	}
}
