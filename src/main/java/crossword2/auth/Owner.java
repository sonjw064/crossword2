package crossword2.auth;

import org.springframework.security.oauth2.jwt.Jwt;

/** 요청을 보낸 주체. 게스트는 UUID, 회원은 사용자 id(문자열)를 id로 쓴다. */
public record Owner(OwnerType type, String id) {

	static final String CLAIM_OWNER_TYPE = "ownerType";

	/** 인증되지 않은 요청이면 null. */
	public static Owner fromJwt(Jwt jwt) {
		if (jwt == null) {
			return null;
		}
		return new Owner(OwnerType.valueOf(jwt.getClaimAsString(CLAIM_OWNER_TYPE)), jwt.getSubject());
	}
}
