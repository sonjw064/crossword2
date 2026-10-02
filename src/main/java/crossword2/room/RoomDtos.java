package crossword2.room;

import java.util.List;

import crossword2.puzzle.PuzzleDtos;
import crossword2.word.Difficulty;
import jakarta.validation.constraints.NotNull;

public final class RoomDtos {

	private RoomDtos() {
	}

	/** 방 만들기/설정 변경 요청. 범위 검증은 {@link RoomSettings#validated}가 한다. */
	public record RoomSettingsRequest(@NotNull Difficulty difficulty, String topic, @NotNull Integer size,
			@NotNull Integer timeLimitSec, @NotNull Integer maxPlayers, @NotNull RoomMode mode) {
	}

	public record ReadyCommand(boolean ready) {
	}

	/** 경기 중 한 단어 제출. 길이 제한은 서버에서 다시 확인한다. */
	public record SubmitCommand(Long entryId, String answer) {
	}

	/** 제출 응답(본인에게만). 맞음/틀림과 점수만 있고 정답은 없다. */
	public record SubmitAck(long entryId, Match.SubmitStatus status, int gained, int bonus, int score, int solved,
			boolean completed) {
	}

	/** 끝난 경기의 순위와 단어 카드(그 경기의 참가자에게만, 경기가 끝난 뒤에만). */
	public record MatchResultView(long puzzleId, List<StandingView> standings, List<EntryCard> cards) {
	}

	public record StandingView(int rank, String nickname, int score, int solved, boolean abandoned, boolean you) {
	}

	public record EntryCard(long entryId, PuzzleDtos.WordCard card) {
	}

	public record CreateRoomResponse(String code, int playerId, RoomView room) {
	}

	/** 입장 응답(본인에게만): 내 playerId, 현재 방 상태, (경기 중이면) 내가 이미 맞힌 단어들 — 새로고침 뒤 칸을 복원하는 데 쓴다. */
	public record JoinAck(int playerId, boolean rejoined, RoomView room, List<SolvedWord> solved) {
	}

	/** 내가 맞힌 단어. 남의 단어는 어디에도 실리지 않는다. */
	public record SolvedWord(long entryId, String word) {
	}

	/** 방 조회(REST). 입장 가능 여부와 요약만 알려주고, {@code you}는 이미 참가자일 때의 내 playerId. */
	public record RoomInfo(String code, RoomStatus status, RoomSettings settings, int playerCount, boolean joinable,
			Integer you) {
	}
}
