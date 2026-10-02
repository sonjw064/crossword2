package crossword2.room;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import crossword2.auth.Owner;
import crossword2.common.ApiException;
import crossword2.puzzle.PlayRestrictions;

/** 진행 중인 경기에 참가한 사용자는 솔로 풀이 기능(힌트, 정답 보기, 단어 카드 등)을 쓸 수 없다. */
@Component
public class RoomPlayRestrictions implements PlayRestrictions {

	private final RoomRegistry registry;

	public RoomPlayRestrictions(RoomRegistry registry) {
		this.registry = registry;
	}

	@Override
	public void requireSoloPlayAllowed(Owner caller) {
		if (caller == null) {
			return;
		}
		boolean inMatch = registry.roomCodeOf(caller).flatMap(registry::find).map(room -> room.isPlaying(caller)).orElse(false);
		if (inMatch) {
			throw new ApiException(HttpStatus.FORBIDDEN, "BATTLE_IN_PROGRESS",
					"learning aids are not available while you are in a match");
		}
	}
}
