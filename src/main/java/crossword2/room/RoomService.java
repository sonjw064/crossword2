package crossword2.room;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import crossword2.auth.Owner;
import crossword2.auth.RateLimiter;
import crossword2.common.ApiException;
import crossword2.room.RoomDtos.CreateRoomResponse;
import crossword2.room.RoomDtos.JoinAck;
import crossword2.room.RoomDtos.RoomInfo;
import crossword2.room.RoomDtos.RoomSettingsRequest;

/** 방 규칙을 적용하고 변경을 방의 모든 참가자에게 방송한다. */
@Service
public class RoomService {

	private static final Logger log = LoggerFactory.getLogger(RoomService.class);
	private static final Duration MINUTE = Duration.ofMinutes(1);
	private static final Duration HOUR = Duration.ofHours(1);
	static final String TOPIC = "/topic/rooms/";

	private final RoomRegistry registry;
	private final RoomProperties props;
	private final RateLimiter rateLimiter;
	private final RoomPuzzleSelector selector;
	private final SimpMessagingTemplate messaging;
	private final Clock clock;

	public RoomService(RoomRegistry registry, RoomProperties props, RateLimiter rateLimiter, RoomPuzzleSelector selector,
			SimpMessagingTemplate messaging, Clock clock) {
		this.registry = registry;
		this.props = props;
		this.rateLimiter = rateLimiter;
		this.selector = selector;
		this.messaging = messaging;
		this.clock = clock;
	}

	// ---------- REST ----------

	/** 방을 만든다. 방장의 자리는 만들어지지만 웹소켓으로 접속(join)하기 전까지는 연결 끊김 상태다. */
	public CreateRoomResponse create(Owner owner, String nickname, RoomSettingsRequest request, String clientIp) {
		rateLimiter.check("room-create:" + key(owner), props.createPerHourPerOwner(), HOUR);
		rateLimiter.check("room-create-ip:" + clientIp, props.createPerHourPerIp(), HOUR);
		RoomSettings settings = toSettings(request);
		requirePuzzleExists(settings);
		Room room = registry.create(owner, nickname, settings, clock.instant());
		return new CreateRoomResponse(room.code(), room.playerIdOf(owner).orElseThrow(), room.view());
	}

	/** 방 정보. 코드를 아는 사람은 입장 가능 여부와 요약을 볼 수 있다(참가자 계정/정답은 없다). */
	public RoomInfo info(String code, Owner owner) {
		Room room = registry.require(code);
		RoomView view = room.view();
		return new RoomInfo(view.code(), view.status(), view.settings(), view.players().size(), room.joinable(),
				room.playerIdOf(owner).isPresent() ? room.playerIdOf(owner).getAsInt() : null);
	}

	// ---------- 웹소켓 명령 ----------

	public JoinAck join(String code, Owner owner, String nickname, String sessionId, String clientIp) {
		rateLimiter.check("room-join:" + key(owner), props.joinPerMinutePerOwner(), MINUTE);
		rateLimiter.check("room-join-ip:" + clientIp, props.joinPerMinutePerIp(), MINUTE);
		Room room = registry.require(code);
		Room.JoinOutcome outcome = registry.join(room, owner, nickname, sessionId, clock.instant());
		RoomView view = room.view();
		broadcast(view.code(), outcome.rejoined() ? RoomEvent.Type.RECONNECT : RoomEvent.Type.JOIN, outcome.playerId(), view);
		return new JoinAck(outcome.playerId(), outcome.rejoined(), view);
	}

	public JoinAck sync(String code, Owner owner) {
		Room room = registry.require(code);
		int playerId = room.playerIdOf(owner)
				.orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "NOT_IN_ROOM", "you are not in this room"));
		return new JoinAck(playerId, true, room.view());
	}

	public void ready(String code, Owner owner, boolean ready) {
		Room room = registry.require(code);
		room.setReady(owner, ready);
		broadcast(room, RoomEvent.Type.READY, owner);
	}

	public void leave(String code, Owner owner) {
		Room room = registry.require(code);
		int playerId = room.playerIdOf(owner).orElse(0);
		Room.LeaveOutcome outcome = registry.leave(room, owner, clock.instant());
		if (!outcome.removed()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "NOT_IN_ROOM", "you are not in this room");
		}
		afterRemoval(room, playerId, outcome.newHost(), RoomEvent.Type.LEAVE);
	}

	public void updateSettings(String code, Owner owner, RoomSettingsRequest request) {
		Room room = registry.require(code);
		RoomSettings settings = toSettings(request);
		requirePuzzleExists(settings);
		room.updateSettings(owner, settings);
		broadcast(room, RoomEvent.Type.SETTINGS, owner);
	}

	/** 시작 조건을 확인하고 퍼즐을 골라 경기를 시작한다(실제 경기 진행은 레이스 모드 구현에서). */
	public void start(String code, Owner owner) {
		Room room = registry.require(code);
		room.checkCanStart(owner);
		RoomPuzzleSelector.Selection selection = selector.pick(room.settings(), room.owners());
		if (selection == null) {
			throw new ApiException(HttpStatus.CONFLICT, "NO_PUZZLE_AVAILABLE", "there is no puzzle for these settings");
		}
		room.markStarted(selection.puzzleId(), clock.instant());
		broadcast(room, RoomEvent.Type.START, owner);
	}

	// ---------- 연결/정리 ----------

	public void onDisconnect(String sessionId) {
		registry.disconnect(sessionId, clock.instant()).ifPresent(binding -> registry.find(binding.code()).ifPresent(room -> {
			RoomView view = room.view();
			broadcast(view.code(), RoomEvent.Type.DISCONNECT, room.playerIdOf(binding.owner()).orElse(0), view);
		}));
	}

	/** 유예 시간이 지난 참가자와 빈 방을 정리하고 남은 사람들에게 알린다. 주기적으로 호출된다. */
	public void sweep() {
		List<RoomRegistry.SweepEvent> events = registry.sweep(clock.instant());
		for (RoomRegistry.SweepEvent e : events) {
			if (e.roomRemoved()) {
				broadcast(e.code(), RoomEvent.Type.CLOSED, null, e.view());
			} else {
				if (e.newHost() != null) {
					broadcast(e.code(), RoomEvent.Type.HOST_CHANGED, null, e.view());
				}
				broadcast(e.code(), RoomEvent.Type.LEAVE, null, e.view());
			}
		}
	}

	/** 게스트가 회원으로 이전되는 등 계정이 바뀌면 방에서 내보낸다(옛 게스트 토큰은 더 이상 쓸 수 없다). */
	public void kick(Owner owner) {
		Optional<Room> room = registry.roomCodeOf(owner).flatMap(registry::find);
		if (room.isEmpty()) {
			return;
		}
		Room r = room.get();
		int playerId = r.playerIdOf(owner).orElse(0);
		Room.LeaveOutcome outcome = registry.leave(r, owner, clock.instant());
		if (outcome.removed()) {
			afterRemoval(r, playerId, outcome.newHost(), RoomEvent.Type.LEAVE);
		}
	}

	private void afterRemoval(Room room, int playerId, Owner newHost, RoomEvent.Type type) {
		RoomView view = room.view();
		if (view.players().isEmpty()) {
			broadcast(view.code(), RoomEvent.Type.CLOSED, playerId, view);
			return;
		}
		if (newHost != null) {
			broadcast(view.code(), RoomEvent.Type.HOST_CHANGED, room.playerIdOf(newHost).orElse(0), view);
		}
		broadcast(view.code(), type, playerId, view);
	}

	// ---------- 공통 ----------

	private RoomSettings toSettings(RoomSettingsRequest r) {
		return RoomSettings.validated(r.difficulty(), r.topic(), r.size(), r.timeLimitSec(), r.maxPlayers(), r.mode(),
				props.enabledModes());
	}

	private void requirePuzzleExists(RoomSettings settings) {
		if (selector.countMatching(settings) == 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "NO_PUZZLE_AVAILABLE",
					"there is no puzzle for the chosen difficulty, topic and size");
		}
	}

	private void broadcast(Room room, RoomEvent.Type type, Owner subject) {
		broadcast(room.code(), type, room.playerIdOf(subject).orElse(0), room.view());
	}

	private void broadcast(String code, RoomEvent.Type type, Integer playerId, RoomView view) {
		try {
			messaging.convertAndSend(TOPIC + code, new RoomEvent(type, playerId, view));
		} catch (RuntimeException e) {
			log.warn("Could not broadcast {} for room {}", type, code, e);
		}
	}

	private static String key(Owner owner) {
		return owner.type() + ":" + owner.id();
	}

	/** 방을 아는 사용자에게 보여줄 수 있는 현재 시각(테스트/로그용). */
	Instant now() {
		return clock.instant();
	}
}
