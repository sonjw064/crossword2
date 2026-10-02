package crossword2.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import crossword2.auth.AuthDtos.MeResponse;

@RestController
@RequestMapping("/api/me")
public class MeController {

	private final AccountService accounts;

	public MeController(AccountService accounts) {
		this.accounts = accounts;
	}

	@GetMapping
	public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
		Account account = accounts.resolve(Owner.fromJwt(jwt));
		return new MeResponse(account.owner().type(), account.owner().id(), account.nickname(), account.email(),
				account.role());
	}
}
