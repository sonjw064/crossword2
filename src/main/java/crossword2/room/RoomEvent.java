package crossword2.room;

/**
 * 방의 모든 참가자에게 방송하는 이벤트(/topic/rooms/{code}). 항상 최신 공개 상태 전체를 담으므로 클라이언트는
 * {@code room.version}이 더 큰 것만 반영하면 된다. {@code playerId}는 이벤트의 주인공(없으면 null).
 */
public record RoomEvent(Type type, Integer playerId, RoomView room) {

	public enum Type {
		JOIN, RECONNECT, LEAVE, DISCONNECT, READY, SETTINGS, HOST_CHANGED, START, SCORE_UPDATE, END, REMATCH, CLOSED
	}
}
