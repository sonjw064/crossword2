package crossword2.room;

import java.util.List;

/**
 * 방의 공개 상태(대기실에 방송하는 내용). 참가자의 계정 ID, 정답, 입력 내용은 들어 있지 않다.
 * {@code puzzleId}와 {@code match}는 경기가 시작된 뒤에만 채워진다(퍼즐 구조와 한국어 힌트는 기존 퍼즐 API로 받는다).
 */
public record RoomView(String code, RoomStatus status, RoomSettings settings, List<PlayerView> players, long version,
		Long puzzleId, MatchView match) {

	public record PlayerView(int playerId, String nickname, boolean host, boolean ready, boolean connected) {
	}

	/**
	 * 경기 진행 상황(공개). 시각은 epoch ms이며 {@code serverNowMs}로 클라이언트 시계와의 차이를 맞춘다.
	 * 사람별 맞힌 개수/점수만 있고, 어떤 단어를 맞혔는지·정답·입력 내용은 없다. {@code rank}는 경기가 끝난 뒤에만 채워진다.
	 */
	public record MatchView(long startsAtMs, long endsAtMs, long serverNowMs, int totalEntries, boolean ended,
			List<ProgressView> progress) {
	}

	public record ProgressView(int playerId, int solved, int score, boolean finished, boolean abandoned, Integer rank) {
	}
}
