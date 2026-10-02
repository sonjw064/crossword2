package crossword2.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import crossword2.auth.AuthDtos.LoginRequest;
import crossword2.auth.AuthDtos.SignupRequest;
import crossword2.auth.AuthDtos.TokenResponse;
import crossword2.common.ApiException;

@Service
public class AuthService {

	private static final Duration MINUTE = Duration.ofMinutes(1);
	private static final Duration HOUR = Duration.ofHours(1);
	private static final int BCRYPT_MAX_BYTES = 72;

	private final GuestAccountRepository guests;
	private final UserRepository users;
	private final TokenService tokens;
	private final GuestMigrationService migration;
	private final PasswordEncoder encoder;
	private final RateLimiter rateLimiter;
	private final RateLimitProperties limits;
	private final Clock clock;
	private final TransactionTemplate tx;
	/** 없는 이메일로 로그인해도 비밀번호 검사와 같은 시간이 걸리도록 하는 더미 해시. */
	private final String dummyHash;

	public AuthService(GuestAccountRepository guests, UserRepository users, TokenService tokens,
			GuestMigrationService migration, PasswordEncoder encoder, RateLimiter rateLimiter,
			RateLimitProperties limits, Clock clock, PlatformTransactionManager transactionManager) {
		this.guests = guests;
		this.users = users;
		this.tokens = tokens;
		this.migration = migration;
		this.encoder = encoder;
		this.rateLimiter = rateLimiter;
		this.limits = limits;
		this.clock = clock;
		this.tx = new TransactionTemplate(transactionManager);
		this.dummyHash = encoder.encode("not-a-real-password-1");
	}

	public TokenResponse createGuest(String nickname, String clientIp) {
		rateLimiter.check("guest:" + clientIp, limits.guestPerHour(), HOUR);
		GuestAccount guest = guests.save(new GuestAccount(nickname.trim(), clock.instant()));
		return tokens.issueNewFamily(Account.guest(guest));
	}

	/**
	 * 회원가입. {@code guestId}가 있으면 그 게스트의 기록을 이어받는다. 회원 생성과 이전은 한 트랜잭션이라
	 * 이전이 실패하면 가입도 취소된다.
	 *
	 * @param guestProof 요청에 실린 Bearer 토큰(없으면 null). 게스트 이전 시 소유 증명으로 쓴다.
	 */
	public TokenResponse signup(SignupRequest request, String clientIp, Jwt guestProof) {
		rateLimiter.check("signup:" + clientIp, limits.signupPerHour(), HOUR);
		if (request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "password is too long");
		}
		String email = normalize(request.email());
		UUID guestId = request.guestId() == null ? null : migration.verifyProof(guestProof, request.guestId());
		if (users.existsByEmail(email)) {
			throw emailTaken();
		}
		String passwordHash = encoder.encode(request.password());
		String nickname = request.nickname().trim();
		User user;
		try {
			user = tx.execute(status -> {
				User created = users.saveAndFlush(new User(email, passwordHash, nickname, clock.instant()));
				if (guestId != null) {
					migration.migrate(guestId, created.getId());
				}
				return created;
			});
		} catch (DataIntegrityViolationException e) {
			throw emailTaken();
		}
		TokenResponse response = tokens.issueNewFamily(Account.member(user));
		return guestId == null ? response : response.withGuestMigrated();
	}

	/** 로그인. 자격 증명이 맞은 뒤에만 게스트 기록을 이어받는다. */
	public TokenResponse login(LoginRequest request, String clientIp, Jwt guestProof) {
		String email = normalize(request.email());
		rateLimiter.check("login-ip:" + clientIp, limits.loginPerMinute(), MINUTE);
		rateLimiter.check("login-email:" + email, limits.loginPerMinute(), MINUTE);
		User user = users.findByEmail(email).orElse(null);
		boolean matches = encoder.matches(request.password(), user == null ? dummyHash : user.getPasswordHash());
		if (user == null || !matches) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "email or password is incorrect");
		}
		UUID guestId = request.guestId() == null ? null : migration.verifyProof(guestProof, request.guestId());
		if (guestId != null) {
			tx.executeWithoutResult(status -> migration.migrate(guestId, user.getId()));
		}
		TokenResponse response = tokens.issueNewFamily(Account.member(user));
		return guestId == null ? response : response.withGuestMigrated();
	}

	public TokenResponse refresh(String refreshToken) {
		return tokens.rotate(refreshToken);
	}

	public void logout(String refreshToken) {
		tokens.revoke(refreshToken);
	}

	private static String normalize(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private static ApiException emailTaken() {
		return new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "this email is already registered");
	}
}
