package crossword2.auth;

import java.util.UUID;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 회원으로 이전된 게스트의 access 토큰을 거부한다(refresh 토큰은 이전 때 폐기된다).
 * 이전 후에도 게스트 토큰으로 새 풀이를 시작하면 그 기록이 주인 없이 남기 때문이다.
 * 게스트 토큰 요청마다 게스트 계정을 기본키로 한 번 조회한다.
 */
class MigratedGuestTokenValidator implements OAuth2TokenValidator<Jwt> {

	private static final OAuth2Error MIGRATED = new OAuth2Error("invalid_token",
			"the guest account has been migrated to a member", null);

	private final GuestAccountRepository guests;

	MigratedGuestTokenValidator(GuestAccountRepository guests) {
		this.guests = guests;
	}

	@Override
	public OAuth2TokenValidatorResult validate(Jwt jwt) {
		if (!OwnerType.GUEST.name().equals(jwt.getClaimAsString(Owner.CLAIM_OWNER_TYPE))) {
			return OAuth2TokenValidatorResult.success();
		}
		try {
			boolean migrated = guests.findById(UUID.fromString(jwt.getSubject()))
					.map(g -> g.getMigratedToUserId() != null).orElse(false);
			return migrated ? OAuth2TokenValidatorResult.failure(MIGRATED) : OAuth2TokenValidatorResult.success();
		} catch (IllegalArgumentException e) {
			return OAuth2TokenValidatorResult.failure(MIGRATED);
		}
	}
}
