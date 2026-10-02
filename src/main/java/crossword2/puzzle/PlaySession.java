package crossword2.puzzle;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** 익명 풀이 세션. 2단계에서 소유자(게스트/회원)가 붙어 기록(PlayRecord)으로 이어진다. */
@Entity
@Table(name = "play_sessions")
public class PlaySession {

	@Id
	private UUID id;

	/** 비관적 잠금을 우회하는 코드가 생겨도 갱신 유실이 조용히 일어나지 않도록 하는 안전장치. */
	@Version
	private Long version;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "puzzle_id")
	private Puzzle puzzle;

	@Column(nullable = false)
	private Instant startedAt;

	private Instant finishedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PlayStatus status = PlayStatus.IN_PROGRESS;

	@Column(nullable = false)
	private int wrongCount;

	@ElementCollection
	@CollectionTable(name = "play_session_solved", joinColumns = @JoinColumn(name = "session_id"))
	@Column(name = "entry_id")
	private Set<Long> solvedEntryIds = new HashSet<>();

	@ElementCollection
	@CollectionTable(name = "play_session_letter_hints", joinColumns = @JoinColumn(name = "session_id"))
	@Column(name = "entry_id")
	private Set<Long> letterHintEntryIds = new HashSet<>();

	@ElementCollection
	@CollectionTable(name = "play_session_definition_hints", joinColumns = @JoinColumn(name = "session_id"))
	@Column(name = "entry_id")
	private Set<Long> definitionHintEntryIds = new HashSet<>();

	protected PlaySession() {
	}

	public PlaySession(Puzzle puzzle, Instant startedAt) {
		this.id = UUID.randomUUID();
		this.puzzle = puzzle;
		this.startedAt = startedAt;
	}

	public boolean isActive() {
		return status == PlayStatus.IN_PROGRESS;
	}

	public void markSolved(Long entryId) {
		solvedEntryIds.add(entryId);
	}

	public void addWrong() {
		wrongCount++;
	}

	/** @return 이번에 새로 집계되었으면 true */
	public boolean addLetterHint(Long entryId) {
		return letterHintEntryIds.add(entryId);
	}

	/** @return 이번에 새로 집계되었으면 true */
	public boolean addDefinitionHint(Long entryId) {
		return definitionHintEntryIds.add(entryId);
	}

	public void finish(PlayStatus finalStatus, Instant now) {
		this.status = finalStatus;
		this.finishedAt = now;
	}

	/** 힌트 사용 수: 첫 글자 힌트와 정의 힌트를 항목별로 한 번씩 센다. */
	public int hintCount() {
		return letterHintEntryIds.size() + definitionHintEntryIds.size();
	}

	public long elapsedSeconds() {
		return Duration.between(startedAt, finishedAt == null ? Instant.now() : finishedAt).toSeconds();
	}

	public UUID getId() {
		return id;
	}

	public Puzzle getPuzzle() {
		return puzzle;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

	public PlayStatus getStatus() {
		return status;
	}

	public int getWrongCount() {
		return wrongCount;
	}

	public Set<Long> getSolvedEntryIds() {
		return solvedEntryIds;
	}
}
