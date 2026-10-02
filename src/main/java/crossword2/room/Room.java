package crossword2.room;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.http.HttpStatus;

import crossword2.auth.Owner;
import crossword2.common.ApiException;

/**
 * 대련 방의 상태와 규칙. Spring과 무관한 순수 객체이며 모든 변경은 이 객체의 잠금 아래에서 한 번에 하나씩 일어난다.
 * 참가자의 계정 정보({@link Owner})는 밖으로 내보내지 않고, 방 안에서만 쓰는 {@code playerId}로 구분한다.
 */
public final class Room {

	/** 참가자. 모든 접근은 {@link Room}의 잠금 안에서 한다. */
	private static final class Player {

		final int playerId;
		final Owner owner;
		String nickname;
		boolean ready;
		boolean connected;
		String sessionId;
		Instant disconnectedAt;

		Player(int playerId, Owner owner, String nickname) {
			this.playerId = playerId;
			this.owner = owner;
			this.nickname = nickname;
		}
	}

	public record JoinOutcome(int playerId, boolean rejoined) {
	}

	/** @param newHost 방장이 바뀌었으면 새 방장(없으면 null) */
	public record LeaveOutcome(boolean removed, Owner newHost) {
	}

	/** 유예 시간이 지나 방에서 제거된 참가자들과 방장 변경. */
	public record SweepOutcome(List<Owner> removed, Owner newHost) {
	}

	private final String code;
	private final Instant createdAt;
	private final Map<Owner, Player> players = new LinkedHashMap<>();
	private RoomSettings settings;
	private RoomStatus status = RoomStatus.WAITING;
	private Owner host;
	private Long puzzleId;
	private Instant startedAt;
	private Match match;
	private MatchOutcome lastOutcome;
	/** 이 방에서 이미 한 퍼즐(재경기에서 같은 퍼즐이 반복되지 않게 한다). */
	private final java.util.Set<Long> playedPuzzleIds = new java.util.HashSet<>();
	/** 마지막으로 시각을 받은 때(경기 화면의 서버 시각 기준). */
	private Instant lastNow;
	private int nextPlayerId = 1;
	private long version;
	/** 연결된 참가자가 한 명도 없게 된 시각. 연결이 있으면 null. */
	private Instant noConnectionSince;

	/** 방장의 자리는 만들 때 생기지만, 실제로 접속(join)하기 전까지는 연결 끊김 상태다. */
	public Room(String code, Owner host, String hostNickname, RoomSettings settings, Instant now) {
		this.code = code;
		this.createdAt = now;
		this.lastNow = now;
		this.settings = settings;
		this.host = host;
		Player p = new Player(nextPlayerId++, host, hostNickname);
		p.disconnectedAt = now;
		players.put(host, p);
		noConnectionSince = now;
	}

	public synchronized String code() {
		return code;
	}

	public synchronized Instant createdAt() {
		return createdAt;
	}

	public synchronized RoomStatus status() {
		return status;
	}

	public synchronized RoomSettings settings() {
		return settings;
	}

	public synchronized long version() {
		return version;
	}

	public synchronized Optional<Long> puzzleId() {
		return Optional.ofNullable(puzzleId);
	}

	public synchronized boolean contains(Owner owner) {
		return players.containsKey(owner);
	}

	public synchronized int playerCount() {
		return players.size();
	}

	public synchronized boolean isHost(Owner owner) {
		return owner.equals(host);
	}

	public synchronized OptionalInt playerIdOf(Owner owner) {
		Player p = players.get(owner);
		return p == null ? OptionalInt.empty() : OptionalInt.of(p.playerId);
	}

	/** 참가자의 계정 목록(퍼즐 선택 등 서버 내부 용도). */
	public synchronized List<Owner> owners() {
		return new ArrayList<>(players.keySet());
	}

	public synchronized boolean joinable() {
		return status == RoomStatus.WAITING && players.size() < settings.maxPlayers();
	}

	// ---------- 입장/퇴장/연결 ----------

	/** 입장하거나(대기 중일 때만), 이미 참가자면 다시 연결한다(재접속). */
	public synchronized JoinOutcome join(Owner owner, String nickname, String sessionId, Instant now) {
		lastNow = now;
		Player existing = players.get(owner);
		if (existing != null) {
			connect(existing, nickname, sessionId);
			version++;
			return new JoinOutcome(existing.playerId, true);
		}
		if (status != RoomStatus.WAITING) {
			throw new ApiException(HttpStatus.CONFLICT, "ROOM_IN_PROGRESS", "the match has already started");
		}
		if (players.size() >= settings.maxPlayers()) {
			throw new ApiException(HttpStatus.CONFLICT, "ROOM_FULL", "the room is full");
		}
		Player p = new Player(nextPlayerId++, owner, nickname);
		players.put(owner, p);
		connect(p, nickname, sessionId);
		version++;
		return new JoinOutcome(p.playerId, false);
	}

	private void connect(Player p, String nickname, String sessionId) {
		p.nickname = nickname;
		p.connected = true;
		p.sessionId = sessionId;
		p.disconnectedAt = null;
		noConnectionSince = null;
	}

	/** 방에서 나간다. 방장이 나가면 가장 먼저 들어온(연결 중인 사람 우선) 참가자가 방장이 된다. */
	public synchronized LeaveOutcome leave(Owner owner, Instant now) {
		lastNow = now;
		Player removed = players.remove(owner);
		if (removed == null) {
			return new LeaveOutcome(false, null);
		}
		if (match != null && !match.isEnded()) {
			match.abandon(removed.playerId);
		}
		version++;
		Owner newHost = reassignHostIfNeeded(owner);
		updateNoConnection(now);
		return new LeaveOutcome(true, newHost);
	}

	/** 연결이 끊겼다. 현재 연결(sessionId)과 같을 때만 반영한다(새 연결이 이미 있으면 옛 연결의 끊김은 무시). */
	public synchronized Optional<Owner> disconnect(String sessionId, Instant now) {
		lastNow = now;
		for (Player p : players.values()) {
			if (p.connected && sessionId.equals(p.sessionId)) {
				p.connected = false;
				p.sessionId = null;
				p.disconnectedAt = now;
				p.ready = false;
				version++;
				updateNoConnection(now);
				return Optional.of(p.owner);
			}
		}
		return Optional.empty();
	}

	private Owner reassignHostIfNeeded(Owner leaving) {
		if (!leaving.equals(host) || players.isEmpty()) {
			return null;
		}
		Player next = players.values().stream().filter(p -> p.connected).findFirst()
				.orElseGet(() -> players.values().iterator().next());
		host = next.owner;
		next.ready = false; // 방장은 준비 표시가 필요 없다
		return host;
	}

	private void updateNoConnection(Instant now) {
		boolean anyConnected = players.values().stream().anyMatch(p -> p.connected);
		if (anyConnected) {
			noConnectionSince = null;
		} else if (noConnectionSince == null) {
			noConnectionSince = now;
		}
	}

	// ---------- 대기실 명령 ----------

	public synchronized void setReady(Owner owner, boolean ready) {
		Player p = requireParticipant(owner);
		requireWaiting();
		if (owner.equals(host)) {
			return; // 방장은 항상 준비된 것으로 본다
		}
		if (!p.connected && ready) {
			throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "a disconnected player cannot be ready");
		}
		p.ready = ready;
		version++;
	}

	/** 방장만, 대기 중에만. 설정이 바뀌면 모두의 준비가 풀린다(바뀐 설정에 동의한 것이 아니므로). */
	public synchronized void updateSettings(Owner owner, RoomSettings newSettings) {
		requireParticipant(owner);
		requireHost(owner);
		requireWaiting();
		if (newSettings.maxPlayers() < players.size()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "SETTINGS_INVALID",
					"maxPlayers cannot be less than the number of players in the room");
		}
		this.settings = newSettings;
		players.values().forEach(p -> p.ready = false);
		version++;
	}

	/** 시작 조건: 방장, 대기 중, 2명 이상, 방장을 뺀 모든 참가자가 연결되어 있고 준비 완료. */
	public synchronized void checkCanStart(Owner owner) {
		requireParticipant(owner);
		requireHost(owner);
		requireWaiting();
		if (players.size() < RoomSettings.MIN_PLAYERS) {
			throw new ApiException(HttpStatus.CONFLICT, "NOT_ENOUGH_PLAYERS", "at least 2 players are needed");
		}
		boolean allReady = players.values().stream()
				.filter(p -> !p.owner.equals(host))
				.allMatch(p -> p.connected && p.ready);
		if (!allReady || !players.get(host).connected) {
			throw new ApiException(HttpStatus.CONFLICT, "NOT_ALL_READY", "every player must be connected and ready");
		}
	}

	/**
	 * 경기를 시작한다. 시작 조건은 {@link #checkCanStart}와 같다(확인과 시작 사이에 상태가 바뀌었을 수 있으므로 다시 확인한다).
	 * 카운트다운이 끝나는 {@code now + countdown}부터 제출할 수 있다.
	 */
	synchronized void startMatch(Owner owner, long chosenPuzzleId, List<Match.Entry> entries, Instant now, Duration countdown) {
		checkCanStart(owner);
		List<Match.Participant> participants = new ArrayList<>();
		for (Player p : players.values()) {
			participants.add(new Match.Participant(p.playerId, p.owner, p.nickname));
		}
		this.match = new Match(entries, participants, now.plus(countdown), Duration.ofSeconds(settings.timeLimitSec()));
		this.status = RoomStatus.PLAYING;
		this.puzzleId = chosenPuzzleId;
		this.startedAt = now;
		this.lastNow = now;
		this.lastOutcome = null; // 새 경기가 진행되는 동안에는 이전 경기의 결과(단어 카드)를 열 수 없다
		this.playedPuzzleIds.add(chosenPuzzleId);
		version++;
	}

	/** 경기 중 한 단어를 제출한다. 점수가 바뀐 경우에만 버전을 올린다(틀린 제출은 방송하지 않는다). */
	public synchronized Match.SubmitResult submit(Owner owner, long entryId, String answer, Instant now) {
		Player p = requireParticipant(owner);
		if (status != RoomStatus.PLAYING || match == null) {
			throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "there is no match in progress");
		}
		lastNow = now;
		Match.SubmitResult result = match.submit(p.playerId, entryId, answer, now);
		if (result.status() == Match.SubmitStatus.CORRECT) {
			version++;
		}
		return result;
	}

	/** 끝날 조건(시간 초과, 푸는 사람이 없음)이면 경기를 끝내고 결과를 돌려준다. 이미 끝났거나 경기 중이 아니면 비어 있다. */
	synchronized java.util.Optional<MatchOutcome> tryFinish(Instant now) {
		if (status != RoomStatus.PLAYING || match == null || !match.isOver(now)) {
			return java.util.Optional.empty();
		}
		lastNow = now;
		List<Match.Standing> standings = match.end();
		status = RoomStatus.ENDED;
		lastOutcome = new MatchOutcome(code, puzzleId, settings.mode(), match.startsAt(), now, standings, match.entryList());
		version++;
		return java.util.Optional.of(lastOutcome);
	}

	/** 방장만, 경기가 끝난 뒤에만: 같은 사람들로 대기실로 돌아간다. */
	public synchronized void rematch(Owner owner) {
		requireParticipant(owner);
		requireHost(owner);
		if (status != RoomStatus.ENDED) {
			throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "a rematch is only possible after the match has ended");
		}
		status = RoomStatus.WAITING;
		match = null;
		puzzleId = null;
		lastOutcome = null;
		players.values().forEach(p -> p.ready = false);
		version++;
	}

	/** 마지막으로 끝난 경기의 결과(그 경기의 참가자였던 사람만). 진행 중이거나 아직 끝나지 않았으면 오류. */
	public synchronized MatchOutcome outcomeFor(Owner owner) {
		if (lastOutcome == null) {
			throw new ApiException(HttpStatus.CONFLICT, "MATCH_NOT_ENDED", "the match has not ended yet");
		}
		boolean participated = lastOutcome.standings().stream().anyMatch(s -> s.owner().equals(owner));
		if (!participated) {
			throw new ApiException(HttpStatus.FORBIDDEN, "NOT_IN_ROOM", "you did not take part in this match");
		}
		return lastOutcome;
	}

	/** 이 참가자가 경기에서 맞힌 단어들(본인에게 보내는 응답용). 경기가 없으면 비어 있다. */
	public synchronized List<RoomDtos.SolvedWord> solvedWordsOf(Owner owner) {
		Player p = players.get(owner);
		if (p == null || match == null) {
			return List.of();
		}
		return match.solvedWords(p.playerId);
	}

	/** 이 사람이 지금 진행 중인 경기의 참가자인가(대련 중에는 학습 기능을 막는다). */
	public synchronized boolean isPlaying(Owner owner) {
		return status == RoomStatus.PLAYING && players.containsKey(owner);
	}

	/** 이미 한 퍼즐들(퍼즐 선택에서 피한다). */
	public synchronized java.util.Set<Long> playedPuzzleIds() {
		return java.util.Set.copyOf(playedPuzzleIds);
	}

	// ---------- 정리 ----------

	/** 연결이 끊긴 채 유예 시간이 지난 참가자를 내보낸다. 경기 중이었다면 그 사람은 경기를 그만둔 것으로 처리한다(점수는 남는다). */
	public synchronized SweepOutcome sweep(Instant now, Duration grace) {
		lastNow = now;
		List<Owner> removed = new ArrayList<>();
		Owner newHost = null;
		for (Iterator<Player> it = players.values().iterator(); it.hasNext();) {
			Player p = it.next();
			if (!p.connected && p.disconnectedAt != null && !p.disconnectedAt.plus(grace).isAfter(now)) {
				it.remove();
				removed.add(p.owner);
				if (match != null && !match.isEnded()) {
					match.abandon(p.playerId);
				}
				if (p.owner.equals(host)) {
					Owner changed = reassignHostIfNeeded(p.owner);
					if (changed != null) {
						newHost = changed;
					}
				}
			}
		}
		if (!removed.isEmpty()) {
			version++;
			updateNoConnection(now);
		}
		return new SweepOutcome(removed, newHost);
	}

	/** 참가자가 없거나, 연결된 참가자가 없는 채 {@code ttl}이 지났으면 삭제해도 된다. */
	public synchronized boolean isExpired(Instant now, Duration ttl) {
		if (players.isEmpty()) {
			return true;
		}
		return noConnectionSince != null && !noConnectionSince.plus(ttl).isAfter(now);
	}

	// ---------- 조회 ----------

	/** 모두에게 방송하는 공개 상태. 계정 정보와 정답은 들어 있지 않다. */
	public synchronized RoomView view() {
		List<RoomView.PlayerView> list = new ArrayList<>();
		for (Player p : players.values()) {
			boolean isHost = p.owner.equals(host);
			list.add(new RoomView.PlayerView(p.playerId, p.nickname, isHost, isHost || p.ready, p.connected));
		}
		boolean started = status != RoomStatus.WAITING && match != null;
		return new RoomView(code, status, settings, list, version, started ? puzzleId : null,
				started ? match.view(lastNow) : null);
	}

	private Player requireParticipant(Owner owner) {
		Player p = players.get(owner);
		if (p == null) {
			throw new ApiException(HttpStatus.FORBIDDEN, "NOT_IN_ROOM", "you are not in this room");
		}
		return p;
	}

	private void requireHost(Owner owner) {
		if (!owner.equals(host)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "NOT_HOST", "only the host can do this");
		}
	}

	private void requireWaiting() {
		if (status != RoomStatus.WAITING) {
			throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "this is only possible while waiting");
		}
	}
}
