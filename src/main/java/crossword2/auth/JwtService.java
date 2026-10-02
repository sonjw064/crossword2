package crossword2.auth;

import java.time.Clock;
import java.time.Instant;

import javax.crypto.SecretKey;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/** access JWT 발급. 검증은 Spring Security의 JwtDecoder가 맡는다. */
@Service
public class JwtService {

	public record AccessToken(String value, long expiresInSeconds) {
	}

	private final JwtEncoder encoder;
	private final Clock clock;
	private final AuthProperties props;

	public JwtService(SecretKey jwtSecretKey, Clock clock, AuthProperties props) {
		this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
		this.clock = clock;
		this.props = props;
	}

	public AccessToken issue(Account account) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(props.accessTokenTtl());
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.issuer(AuthConfig.ISSUER)
				.issuedAt(now)
				.expiresAt(expiresAt)
				.subject(account.owner().id())
				.claim(Owner.CLAIM_OWNER_TYPE, account.owner().type().name())
				.claim("nickname", account.nickname());
		if (account.role() != null) {
			claims.claim("role", account.role().name());
		}
		String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
				.getTokenValue();
		return new AccessToken(token, props.accessTokenTtl().toSeconds());
	}
}
