package crossword2.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import crossword2.auth.AuthDtos.GuestRequest;
import crossword2.auth.AuthDtos.LoginRequest;
import crossword2.auth.AuthDtos.RefreshRequest;
import crossword2.auth.AuthDtos.SignupRequest;
import crossword2.auth.AuthDtos.TokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/guest")
	@ResponseStatus(HttpStatus.CREATED)
	public TokenResponse guest(@Valid @RequestBody GuestRequest request, HttpServletRequest http) {
		return authService.createGuest(request.nickname(), http.getRemoteAddr());
	}

	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public TokenResponse signup(@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
		return authService.signup(request, http.getRemoteAddr());
	}

	@PostMapping("/login")
	public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
		return authService.login(request, http.getRemoteAddr());
	}

	@PostMapping("/refresh")
	public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
		return authService.refresh(request.refreshToken());
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@Valid @RequestBody RefreshRequest request) {
		authService.logout(request.refreshToken());
	}
}
