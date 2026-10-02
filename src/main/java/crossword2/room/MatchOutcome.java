package crossword2.room;

import java.time.Instant;
import java.util.List;

/**
 * 끝난 경기의 결과(서버 내부용). 정답 단어는 들어 있지 않고 항목/단어 ID만 있다. 계정 정보는 저장과 권한 확인에만 쓰며
 * 방송하는 {@link RoomView}에는 실리지 않는다.
 */
record MatchOutcome(String code, long puzzleId, RoomMode mode, Instant startedAt, Instant endedAt,
		List<Match.Standing> standings, List<Match.Entry> entries) {
}
