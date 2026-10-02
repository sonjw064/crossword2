package crossword2.room;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;

import crossword2.auth.Owner;
import crossword2.common.ApiException;

/**
 * 레이스 모드 경기 한 판의 상태와 점수 규칙. Spring과 무관한 순수 객체이며 스레드 안전하지 않다
 * ({@link Room}의 잠금 안에서만 쓴다). 정답 단어는 이 객체 안에만 있고 어떤 공개 상태({@link RoomView.MatchView})에도 실리지 않는다.
 *
 * <p>점수: 단어를 맞히면 글자 수 × 10점, 퍼즐을 먼저 완성한 사람에게 순서대로 보너스(1등 100, 2등 60, 3등 30).
 * 틀려도 감점은 없지만 1초에 {@value #MAX_SUBMITS_PER_SECOND}번까지만 제출할 수 있다.
 * 순위는 점수, 같으면 마지막 정답을 먼저 맞힌 사람, 그래도 같으면 먼저 들어온 사람 순이다.
 */
final class Match {

	static final int POINTS_PER_LETTER = 10;
	static final int[] FINISH_BONUS = { 100, 60, 30 };
	static final int MAX_SUBMITS_PER_SECOND = 3;
	private static final Duration RATE_WINDOW = Duration.ofSeconds(1);

	/** 한 단어: 퍼즐 항목 ID, 단어 ID, 정답(소문자). 정답은 toString/접근자로 밖에 나가지 않도록 이 패키지 안에서만 비교에 쓴다. */
	static final class Entry {

		final long entryId;
		final long wordId;
		final int length;
		private final String answer;

		Entry(long entryId, long wordId, String answer) {
			this.entryId = entryId;
			this.wordId = wordId;
			this.answer = answer.toLowerCase(Locale.ROOT);
			this.length = this.answer.length();
		}

		boolean matches(String given) {
			return answer.equals(given);
		}

		@Override
		public String toString() {
			return "Entry[" + entryId + "]";
		}
	}

	enum SubmitStatus {
		CORRECT, WRONG, INCOMPLETE, ALREADY_SOLVED
	}

	record SubmitResult(long entryId, SubmitStatus status, int gained, int bonus, int score, int solved, boolean completed) {
	}

	/** 경기에 참가한 사람. 방을 나가도 기록은 남는다. */
	static final class Participant {

		final int playerId;
		final Owner owner;
		final String nickname;
		final Set<Long> solved = new HashSet<>();
		int score;
		Instant lastCorrectAt;
		Instant finishedAt;
		boolean abandoned;
		int finishOrder;
		final Deque<Instant> recentSubmits = new ArrayDeque<>();

		Participant(int playerId, Owner owner, String nickname) {
			this.playerId = playerId;
			this.owner = owner;
			this.nickname = nickname;
		}

		boolean active() {
			return !abandoned && finishedAt == null;
		}
	}

	record Standing(int rank, int playerId, Owner owner, String nickname, int score, int solved, Instant finishedAt,
			boolean abandoned) {
	}

	private final Map<Long, Entry> entries = new LinkedHashMap<>();
	private final Map<Integer, Participant> participants = new LinkedHashMap<>();
	private final Instant startsAt;
	private final Instant endsAt;
	private int finishedCount;
	private boolean ended;

	Match(List<Entry> entryList, List<Participant> participantList, Instant startsAt, Duration timeLimit) {
		entryList.forEach(e -> entries.put(e.entryId, e));
		participantList.forEach(p -> participants.put(p.playerId, p));
		this.startsAt = startsAt;
		this.endsAt = startsAt.plus(timeLimit);
	}

	Instant startsAt() {
		return startsAt;
	}

	Instant endsAt() {
		return endsAt;
	}

	int totalEntries() {
		return entries.size();
	}

	List<Entry> entryList() {
		return List.copyOf(entries.values());
	}

	List<Participant> participantList() {
		return List.copyOf(participants.values());
	}

	boolean hasParticipant(int playerId) {
		return participants.containsKey(playerId);
	}

	boolean isEnded() {
		return ended;
	}

	/** 한 단어를 제출한다. 상태가 바뀌었는지는 결과의 status가 CORRECT인지로 알 수 있다. */
	SubmitResult submit(int playerId, long entryId, String answer, Instant now) {
		Participant p = participants.get(playerId);
		if (p == null) {
			throw new ApiException(HttpStatus.FORBIDDEN, "NOT_IN_ROOM", "you are not in this match");
		}
		if (ended || !now.isBefore(endsAt)) {
			throw new ApiException(HttpStatus.CONFLICT, "MATCH_OVER", "the match is over");
		}
		if (now.isBefore(startsAt)) {
			throw new ApiException(HttpStatus.CONFLICT, "MATCH_NOT_STARTED", "the match has not started yet");
		}
		if (p.abandoned || p.finishedAt != null) {
			throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "you are no longer playing in this match");
		}
		while (!p.recentSubmits.isEmpty() && !p.recentSubmits.peekFirst().plus(RATE_WINDOW).isAfter(now)) {
			p.recentSubmits.pollFirst();
		}
		if (p.recentSubmits.size() >= MAX_SUBMITS_PER_SECOND) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "you are submitting too fast");
		}
		p.recentSubmits.addLast(now);

		Entry entry = entries.get(entryId);
		if (entry == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ENTRY", "entry does not belong to this puzzle");
		}
		String given = answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
		if (p.solved.contains(entryId)) {
			return result(p, entryId, SubmitStatus.ALREADY_SOLVED, 0, 0);
		}
		if (given.length() != entry.length) {
			return result(p, entryId, SubmitStatus.INCOMPLETE, 0, 0);
		}
		if (!entry.matches(given)) {
			return result(p, entryId, SubmitStatus.WRONG, 0, 0);
		}
		p.solved.add(entryId);
		int gained = entry.length * POINTS_PER_LETTER;
		p.score += gained;
		p.lastCorrectAt = now;
		int bonus = 0;
		if (p.solved.size() == entries.size()) {
			p.finishedAt = now;
			p.finishOrder = ++finishedCount;
			bonus = p.finishOrder <= FINISH_BONUS.length ? FINISH_BONUS[p.finishOrder - 1] : 0;
			p.score += bonus;
		}
		return result(p, entryId, SubmitStatus.CORRECT, gained, bonus);
	}

	private SubmitResult result(Participant p, long entryId, SubmitStatus status, int gained, int bonus) {
		return new SubmitResult(entryId, status, gained, bonus, p.score, p.solved.size(), p.finishedAt != null);
	}

	/** 경기를 그만둔다(방을 나가거나 재접속 유예가 끝남). 점수는 순위에 남는다. */
	void abandon(int playerId) {
		Participant p = participants.get(playerId);
		if (p != null && p.finishedAt == null) {
			p.abandoned = true;
		}
	}

	/** 시간이 다 됐거나, 아직 푸는 사람이 아무도 없으면 끝난 것이다. */
	boolean isOver(Instant now) {
		if (ended) {
			return true;
		}
		return !now.isBefore(endsAt) || participants.values().stream().noneMatch(Participant::active);
	}

	/** 경기를 끝내고 최종 순위를 돌려준다. 한 번 끝나면 더 제출할 수 없다. */
	List<Standing> end() {
		ended = true;
		return standings();
	}

	List<Standing> standings() {
		List<Participant> sorted = new ArrayList<>(participants.values());
		Comparator<Participant> order = Comparator.<Participant>comparingInt(p -> -p.score)
				.thenComparing(p -> p.lastCorrectAt, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparingInt(p -> p.playerId);
		sorted.sort(order);
		List<Standing> out = new ArrayList<>();
		for (int i = 0; i < sorted.size(); i++) {
			Participant p = sorted.get(i);
			out.add(new Standing(i + 1, p.playerId, p.owner, p.nickname, p.score, p.solved.size(), p.finishedAt, p.abandoned));
		}
		return out;
	}

	/** 이 사람이 맞힌 단어(항목 ID와 단어). 본인에게만 돌려준다: 이미 직접 맞힌 단어라 새로 알려지는 정답이 아니다. */
	List<RoomDtos.SolvedWord> solvedWords(int playerId) {
		Participant p = participants.get(playerId);
		if (p == null) {
			return List.of();
		}
		return entries.values().stream().filter(e -> p.solved.contains(e.entryId))
				.map(e -> new RoomDtos.SolvedWord(e.entryId, e.answer)).toList();
	}

	/** 공개 상태: 사람별 맞힌 개수/점수/완성 여부만. 어떤 단어를 맞혔는지는 싣지 않는다. */
	RoomView.MatchView view(Instant serverNow) {
		List<Standing> ranking = standings();
		List<RoomView.ProgressView> progress = new ArrayList<>();
		for (Participant p : participants.values()) {
			Integer rank = ended ? ranking.stream().filter(s -> s.playerId() == p.playerId).findFirst().get().rank() : null;
			progress.add(new RoomView.ProgressView(p.playerId, p.solved.size(), p.score, p.finishedAt != null, p.abandoned, rank));
		}
		return new RoomView.MatchView(startsAt.toEpochMilli(), endsAt.toEpochMilli(), serverNow.toEpochMilli(), entries.size(),
				ended, progress);
	}
}
