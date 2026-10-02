package crossword2.room;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 끝난 대련 한 판의 기록. 참가자별 점수는 {@link MatchParticipant}. */
@Entity
@Table(name = "match_results")
public class MatchResult {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long puzzleId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private RoomMode mode;

	@Column(nullable = false)
	private Instant startedAt;

	@Column(nullable = false)
	private Instant endedAt;

	@Column(nullable = false)
	private int playerCount;

	protected MatchResult() {
	}

	MatchResult(Long puzzleId, RoomMode mode, Instant startedAt, Instant endedAt, int playerCount) {
		this.puzzleId = puzzleId;
		this.mode = mode;
		this.startedAt = startedAt;
		this.endedAt = endedAt;
		this.playerCount = playerCount;
	}

	public Long getId() {
		return id;
	}

	public Long getPuzzleId() {
		return puzzleId;
	}

	public RoomMode getMode() {
		return mode;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getEndedAt() {
		return endedAt;
	}

	public int getPlayerCount() {
		return playerCount;
	}
}
