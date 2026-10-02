package crossword2.room;

import java.security.Principal;
import java.time.Instant;

import crossword2.auth.Owner;

/**
 * 웹소켓 연결의 인증된 주체. CONNECT 때 검증한 JWT에서 만들고, 토큰 만료 시각을 기억해
 * 이후 모든 메시지에서 만료를 확인한다(만료되면 연결을 끊고 클라이언트가 토큰을 갱신해 다시 접속한다).
 */
public record RoomPrincipal(Owner owner, String nickname, Instant expiresAt) implements Principal {

	@Override
	public String getName() {
		return owner.type() + ":" + owner.id();
	}

	static RoomPrincipal of(Principal principal) {
		if (principal instanceof RoomPrincipal p) {
			return p;
		}
		throw new IllegalStateException("the connection is not authenticated");
	}
}
