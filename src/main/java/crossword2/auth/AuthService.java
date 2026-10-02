package crossword2.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

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
	private final PasswordEncoder encoder;
	private final RateLimiter rateLimiter;
	private final RateLimitProperties limits;
	private final Clock clock;
	/** 없는 이메일로 로그인해도 비밀번호 검사와 같은 시간이 걸리도록 하는 더미 해시. */
	private final String dummyHash;

	public AuthService(GuestAccountRepository guests, UserRepository users, TokenService tokens,
			PasswordEncoder encoder, RateLimiter rateLimiter, RateLimitProperties limits, Clock clock) {
		this.guests = guests;
		this.users = users;
		this.tokens = tokens;
		this.encoder = encoder;
		this.rateLimiter = rateLimiter;
		this.limits = limits;
		this.clock = clock;
		this.dummyHash = encoder.encode("not-a-real-password-1");
	}

	public TokenResponse createGuest(String nickname, String clientIp) {
		rateLimiter.check("guest:" + clientIp, limits.guestPerHour(), HOUR);
		GuestAccount guest = guests.save(new GuestAccount(nickname.trim(), clock.instant()));
		return tokens.issueNewFamily(Account.guest(guest));
	}

	public TokenResponse signup(SignupRequest request, String clientIp) {
		rateLimiter.check("signup:" + clientIp, limits.signupPerHour(), HOUR);
		if (request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "password is too long");
		}
		String email = normalize(request.email());
		if (users.existsByEmail(email)) {
			throw emailTaken();
		}
		User user;
		try {
			user = users.saveAndFlush(
					new User(email, encoder.encode(request.password()), request.nickname().trim(), clock.instant()));
		} catch (DataIntegrityViolationException e) {
			throw emailTaken();
		}
		return tokens.issueNewFamily(Account.member(user));
	}

	public TokenResponse login(LoginRequest request, String clientIp) {
		String email = normalize(request.email());
		rateLimiter.check("login-ip:" + clientIp, limits.loginPerMinute(), MINUTE);
		rateLimiter.check("login-email:" + email, limits.loginPerMinute(), MINUTE);
		User user = users.findByEmail(email).orElse(null);
		boolean matches = encoder.matches(request.password(), user == null ? dummyHash : user.getPasswordHash());
		if (user == null || !matches) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "email or password is incorrect");
		}
		return tokens.issueNewFamily(Account.member(user));
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
