package crossword2.room;

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

	public record CreateRoomResponse(String code, int playerId, RoomView room) {
	}

	/** 입장 응답(본인에게만): 내 playerId와 현재 방 상태. */
	public record JoinAck(int playerId, boolean rejoined, RoomView room) {
	}

	/** 방 조회(REST). 입장 가능 여부와 요약만 알려주고, {@code you}는 이미 참가자일 때의 내 playerId. */
	public record RoomInfo(String code, RoomStatus status, RoomSettings settings, int playerCount, boolean joinable,
			Integer you) {
	}
}
