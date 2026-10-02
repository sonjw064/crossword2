package crossword2.room;

import java.time.Instant;

import crossword2.auth.Owner;
import crossword2.auth.OwnerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** 대련 한 판에 참가한 사람의 결과. 소유자(게스트/회원)가 있으므로 게스트 → 회원 이전 대상이다({@link MatchMigrator}). */
@Entity
@Table(name = "match_participants", indexes = {
		@Index(name = "idx_match_participants_owner", columnList = "owner_type, owner_id"),
		@Index(name = "idx_match_participants_match", columnList = "match_id") })
public class MatchParticipant {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long matchId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OwnerType ownerType;

	@Column(nullable = false, length = 36)
	private String ownerId;

	@Column(nullable = false, length = 40)
	private String nickname;

	@Column(nullable = false)
	private int score;

	@Column(nullable = false)
	private int solvedCount;

	@Column(nullable = false, name = "final_rank")
	private int rank;

	private Instant finishedAt;

	@Column(nullable = false)
	private boolean abandoned;

	protected MatchParticipant() {
	}

	MatchParticipant(Long matchId, Owner owner, String nickname, int score, int solvedCount, int rank, Instant finishedAt,
			boolean abandoned) {
		this.matchId = matchId;
		this.ownerType = owner.type();
		this.ownerId = owner.id();
		this.nickname = nickname;
		this.score = score;
		this.solvedCount = solvedCount;
		this.rank = rank;
		this.finishedAt = finishedAt;
		this.abandoned = abandoned;
	}

	public Long getId() {
		return id;
	}

	public Long getMatchId() {
		return matchId;
	}

	public OwnerType getOwnerType() {
		return ownerType;
	}

	public String getOwnerId() {
		return ownerId;
	}

	public String getNickname() {
		return nickname;
	}

	public int getScore() {
		return score;
	}

	public int getSolvedCount() {
		return solvedCount;
	}

	public int getRank() {
		return rank;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

	public boolean isAbandoned() {
		return abandoned;
	}
}
