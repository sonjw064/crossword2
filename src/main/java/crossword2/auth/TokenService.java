package crossword2.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crossword2.auth.AuthDtos.TokenResponse;
import crossword2.auth.JwtService.AccessToken;
import crossword2.common.ApiException;

/**
 * access/refresh 토큰 발급과 refresh 교체. refresh 토큰은 한 번 쓰면 폐기되고 새 토큰으로 교체된다.
 * 이미 폐기된 토큰이 다시 오면 탈취로 보고 같은 family의 토큰을 모두 폐기한다.
 */
@Service
public class TokenService {

	private final RefreshTokenRepository tokens;
	private final JwtService jwt;
	private final AccountService accounts;
	private final AuthProperties props;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();

	public TokenService(RefreshTokenRepository tokens, JwtService jwt, AccountService accounts, AuthProperties props,
			Clock clock) {
		this.tokens = tokens;
		this.jwt = jwt;
		this.accounts = accounts;
		this.props = props;
		this.clock = clock;
	}

	/** 새 로그인(새 family)용 토큰을 발급한다. */
	@Transactional
	public TokenResponse issueNewFamily(Account account) {
		return issue(account, UUID.randomUUID());
	}

	/** refresh 토큰을 새 토큰 쌍으로 교체한다. 재사용이 감지되면 family 전체를 폐기하므로 롤백하지 않는다. */
	@Transactional(noRollbackFor = ApiException.class)
	public TokenResponse rotate(String rawRefreshToken) {
		RefreshToken stored = tokens.findByHashForUpdate(hash(rawRefreshToken)).orElseThrow(TokenService::invalid);
		Instant now = clock.instant();
		if (stored.getRevokedAt() != null) {
			tokens.revokeFamily(stored.getFamilyId(), now);
			throw invalid();
		}
		if (!stored.getExpiresAt().isAfter(now)) {
			throw invalid();
		}
		Account account = accounts.resolve(stored.owner());
		stored.revoke(now);
		return issue(account, stored.getFamilyId());
	}

	/** 로그아웃: 이 토큰이 속한 family를 폐기한다. 모르는 토큰이어도 조용히 넘어간다. */
	@Transactional
	public void revoke(String rawRefreshToken) {
		// 교체(rotate)와 같은 행 잠금을 잡아, 동시에 교체된 새 토큰까지 함께 폐기되도록 직렬화한다
		tokens.findByHashForUpdate(hash(rawRefreshToken))
				.ifPresent(t -> tokens.revokeFamily(t.getFamilyId(), clock.instant()));
	}

	private TokenResponse issue(Account account, UUID familyId) {
		Instant now = clock.instant();
		Duration ttl = account.owner().type() == OwnerType.GUEST ? props.guestRefreshTtl() : props.memberRefreshTtl();
		String raw = newRawToken();
		tokens.save(new RefreshToken(hash(raw), familyId, account.owner(), now, now.plus(ttl)));
		AccessToken access = jwt.issue(account);
		return new TokenResponse(access.value(), access.expiresInSeconds(), raw, account.owner().type(),
				account.owner().id(), account.nickname(), account.role(), false);
	}

	private String newRawToken() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String hash(String raw) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static ApiException invalid() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "refresh token is invalid or expired");
	}
}
