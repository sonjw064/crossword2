package crossword2.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

	/** 2~20자, 글자/숫자/밑줄/하이픈/공백(처음과 끝은 공백 불가). 화면에는 textContent로만 표시한다. */
	static final String NICKNAME = "^[\\p{L}\\p{N}_-][\\p{L}\\p{N}_ -]{0,18}[\\p{L}\\p{N}_-]$";
	/** 글자와 숫자를 각각 하나 이상 포함. */
	/** 소문자/대문자 16진수의 표준 UUID 형식. */
	static final String UUID_FORMAT = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
	static final String PASSWORD_POLICY = "^(?=.*\\p{L})(?=.*\\d).+$";

	private AuthDtos() {
	}

	public record GuestRequest(@NotBlank @Pattern(regexp = NICKNAME) String nickname) {
	}

	public record SignupRequest(
			@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(min = 8, max = 72) @Pattern(regexp = PASSWORD_POLICY) String password,
			@NotBlank @Pattern(regexp = NICKNAME) String nickname,
			@Pattern(regexp = UUID_FORMAT) String guestId) {
	}

	/** {@code guestId}가 있으면 게스트 기록을 이어받는다. 이때 요청에 그 게스트의 Bearer 토큰이 있어야 한다. */
	public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 72) String password,
			@Pattern(regexp = UUID_FORMAT) String guestId) {
	}

	public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {
	}

	/** {@code role}은 회원만, {@code expiresIn}은 access 토큰 유효 시간(초), {@code guestMigrated}는 이번 요청에서 게스트 기록을 이어받았는지. */
	public record TokenResponse(String accessToken, long expiresIn, String refreshToken, OwnerType ownerType,
			String ownerId, String nickname, Role role, boolean guestMigrated) {

		public TokenResponse withGuestMigrated() {
			return new TokenResponse(accessToken, expiresIn, refreshToken, ownerType, ownerId, nickname, role, true);
		}
	}

	public record MeResponse(OwnerType ownerType, String id, String nickname, String email, Role role) {
	}
}
