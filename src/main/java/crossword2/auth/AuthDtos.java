package crossword2.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

	/** 2~20자, 글자/숫자/밑줄/하이픈/공백(처음과 끝은 공백 불가). 화면에는 textContent로만 표시한다. */
	static final String NICKNAME = "^[\\p{L}\\p{N}_-][\\p{L}\\p{N}_ -]{0,18}[\\p{L}\\p{N}_-]$";
	/** 글자와 숫자를 각각 하나 이상 포함. */
	static final String PASSWORD_POLICY = "^(?=.*\\p{L})(?=.*\\d).+$";

	private AuthDtos() {
	}

	public record GuestRequest(@NotBlank @Pattern(regexp = NICKNAME) String nickname) {
	}

	public record SignupRequest(
			@NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(min = 8, max = 72) @Pattern(regexp = PASSWORD_POLICY) String password,
			@NotBlank @Pattern(regexp = NICKNAME) String nickname) {
	}

	public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {
	}

	public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {
	}

	/** {@code role}은 회원만, {@code expiresIn}은 access 토큰 유효 시간(초). */
	public record TokenResponse(String accessToken, long expiresIn, String refreshToken, OwnerType ownerType,
			String ownerId, String nickname, Role role) {
	}

	public record MeResponse(OwnerType ownerType, String id, String nickname, String email, Role role) {
	}
}
