package crossword2.progress;

import java.time.Instant;
import java.util.UUID;

import crossword2.auth.Owner;
import crossword2.auth.OwnerType;
import crossword2.puzzle.PlayStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** 끝난 풀이(완료 또는 정답 보기) 한 건의 기록. 세션 하나당 최대 한 건이다. */
@Entity
@Table(name = "play_records", indexes = @Index(name = "idx_play_records_owner", columnList = "owner_type, owner_id, completed_at"))
public class PlayRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private UUID sessionId;

	@Column(nullable = false)
	private Long puzzleId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OwnerType ownerType;

	@Column(nullable = false, length = 36)
	private String ownerId;

	/** COMPLETED 또는 GAVE_UP. */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PlayStatus status;

	@Column(nullable = false)
	private long elapsedSec;

	@Column(nullable = false)
	private int hintCount;

	@Column(nullable = false)
	private int wrongCount;

	@Column(nullable = false)
	private Instant completedAt;

	protected PlayRecord() {
	}

	public PlayRecord(UUID sessionId, Long puzzleId, Owner owner, PlayStatus status, long elapsedSec, int hintCount,
			int wrongCount, Instant completedAt) {
		this.sessionId = sessionId;
		this.puzzleId = puzzleId;
		this.ownerType = owner.type();
		this.ownerId = owner.id();
		this.status = status;
		this.elapsedSec = elapsedSec;
		this.hintCount = hintCount;
		this.wrongCount = wrongCount;
		this.completedAt = completedAt;
	}

	public Long getId() {
		return id;
	}

	public UUID getSessionId() {
		return sessionId;
	}

	public Long getPuzzleId() {
		return puzzleId;
	}

	public OwnerType getOwnerType() {
		return ownerType;
	}

	public String getOwnerId() {
		return ownerId;
	}

	public PlayStatus getStatus() {
		return status;
	}

	public long getElapsedSec() {
		return elapsedSec;
	}

	public int getHintCount() {
		return hintCount;
	}

	public int getWrongCount() {
		return wrongCount;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}
}
