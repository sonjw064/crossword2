package crossword2.room;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import crossword2.auth.Owner;
import crossword2.common.ApiException;

/**
 * 모든 방과 "사용자 → 방", "웹소켓 세션 → 사용자" 대응을 서버 메모리에 보관한다.
 * 방의 구성(생성/입장/퇴장/삭제)을 바꾸는 작업은 이 객체의 잠금 아래에서 하므로 대응표가 방 상태와 어긋나지 않는다.
 * 한 사용자는 한 방에만 있을 수 있다.
 */
@Component
public class RoomRegistry {

	private static final int CODE_ATTEMPTS = 50;

	/** 웹소켓 세션이 어느 방의 누구에게 속하는지. */
	public record Binding(String code, Owner owner) {
	}

	public record SweepEvent(String code, List<Owner> removed, Owner newHost, boolean roomRemoved, RoomView view) {
	}

	private final Map<String, Room> rooms = new ConcurrentHashMap<>();
	private final Map<Owner, String> roomByOwner = new ConcurrentHashMap<>();
	private final Map<String, Binding> bySession = new ConcurrentHashMap<>();
	private final RoomProperties props;

	public RoomRegistry(RoomProperties props) {
		this.props = props;
	}

	public synchronized Room create(Owner host, String nickname, RoomSettings settings, Instant now) {
		requireNotInAnotherRoom(host, null);
		if (rooms.size() >= props.maxRooms()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ROOM_LIMIT", "too many rooms right now, please try again later");
		}
		for (int i = 0; i < CODE_ATTEMPTS; i++) {
			String code = RoomCodes.generate();
			if (!rooms.containsKey(code)) {
				Room room = new Room(code, host, nickname, settings, now);
				rooms.put(code, room);
				roomByOwner.put(host, code);
				return room;
			}
		}
		throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ROOM_LIMIT", "could not allocate a room code");
	}

	public Optional<Room> find(String code) {
		return code == null ? Optional.empty() : Optional.ofNullable(rooms.get(code));
	}

	/** 형식이 틀리거나 없는 코드는 모두 같은 404다(코드를 추측하는 시도에 단서를 주지 않는다). */
	public Room require(String rawCode) {
		String code = RoomCodes.normalize(rawCode);
		return find(code).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "room not found"));
	}

	public Optional<String> roomCodeOf(Owner owner) {
		String code = roomByOwner.get(owner);
		return code != null && rooms.containsKey(code) ? Optional.of(code) : Optional.empty();
	}

	public int roomCount() {
		return rooms.size();
	}

	public synchronized Room.JoinOutcome join(Room room, Owner owner, String nickname, String sessionId, Instant now) {
		requireNotInAnotherRoom(owner, room.code());
		Room.JoinOutcome outcome = room.join(owner, nickname, sessionId, now);
		roomByOwner.put(owner, room.code());
		unbindSessionsOf(owner);
		bySession.put(sessionId, new Binding(room.code(), owner));
		return outcome;
	}

	public synchronized Room.LeaveOutcome leave(Room room, Owner owner, Instant now) {
		Room.LeaveOutcome outcome = room.leave(owner, now);
		if (outcome.removed()) {
			forget(owner);
			if (room.playerCount() == 0) {
				rooms.remove(room.code());
			}
		}
		return outcome;
	}

	/** 연결이 끊겼다. 자리는 유지된다(유예 시간 안에 재접속할 수 있다). */
	public synchronized Optional<Binding> disconnect(String sessionId, Instant now) {
		Binding binding = bySession.remove(sessionId);
		if (binding == null) {
			return Optional.empty();
		}
		Room room = rooms.get(binding.code());
		if (room == null || room.disconnect(sessionId, now).isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(binding);
	}

	/** 유예 시간이 지난 참가자와 비어 있는 방을 정리한다. */
	public synchronized List<SweepEvent> sweep(Instant now) {
		List<SweepEvent> events = new ArrayList<>();
		for (Room room : new ArrayList<>(rooms.values())) {
			Room.SweepOutcome outcome = room.sweep(now, props.reconnectGrace());
			outcome.removed().forEach(this::forget);
			boolean expired = room.isExpired(now, props.emptyRoomTtl());
			if (expired) {
				room.owners().forEach(this::forget);
				rooms.remove(room.code());
			}
			if (!outcome.removed().isEmpty() || expired) {
				events.add(new SweepEvent(room.code(), outcome.removed(), outcome.newHost(), expired, room.view()));
			}
		}
		return events;
	}

	/** 사용자를 소속된 방에서 내보낸다(게스트가 회원으로 이전되는 등). 방이 있었다면 그 방을 돌려준다. */
	public synchronized Optional<Room> removeOwner(Owner owner, Instant now) {
		return roomCodeOf(owner).map(rooms::get).filter(r -> leave(r, owner, now).removed());
	}

	private void requireNotInAnotherRoom(Owner owner, String targetCode) {
		String current = roomByOwner.get(owner);
		if (current != null && rooms.containsKey(current) && !current.equals(targetCode)) {
			throw new ApiException(HttpStatus.CONFLICT, "ALREADY_IN_ROOM", "you are already in room " + current);
		}
	}

	private void forget(Owner owner) {
		roomByOwner.remove(owner);
		unbindSessionsOf(owner);
	}

	private void unbindSessionsOf(Owner owner) {
		bySession.values().removeIf(b -> b.owner().equals(owner));
	}

	/** 테스트용: 모든 방을 지운다. */
	synchronized void clear() {
		rooms.clear();
		roomByOwner.clear();
		bySession.clear();
	}

	Duration reconnectGrace() {
		return props.reconnectGrace();
	}
}
