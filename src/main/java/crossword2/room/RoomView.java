package crossword2.room;

import java.util.List;

/**
 * 방의 공개 상태(대기실에 방송하는 내용). 참가자의 계정 ID, 정답, 입력 내용은 들어 있지 않다.
 * {@code puzzleId}는 경기가 시작된 뒤에만 채워진다(퍼즐 구조와 한국어 힌트는 기존 퍼즐 API로 받는다).
 */
public record RoomView(String code, RoomStatus status, RoomSettings settings, List<PlayerView> players, long version,
		Long puzzleId) {

	public record PlayerView(int playerId, String nickname, boolean host, boolean ready, boolean connected) {
	}
}
