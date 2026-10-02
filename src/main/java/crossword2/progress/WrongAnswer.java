package crossword2.progress;

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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 오답노트의 한 단어. {@code count}는 이 단어에서 실수한 <b>판(세션) 수</b>다(한 판에서 여러 번 틀려도 1).
 * 실수 = 틀린 적 있음, 힌트(첫 글자/정의) 사용, 정답 보기로 끝날 때까지 못 맞힘. (사용자, 단어)마다 한 행이다.
 */
@Entity
@Table(name = "wrong_answers", uniqueConstraints = @UniqueConstraint(columnNames = { "owner_type", "owner_id", "word_id" }))
public class WrongAnswer {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OwnerType ownerType;

	@Column(nullable = false, length = 36)
	private String ownerId;

	@Column(nullable = false)
	private Long wordId;

	@Column(name = "miss_count", nullable = false)
	private int count;

	@Column(nullable = false)
	private Instant lastAt;

	protected WrongAnswer() {
	}

	public WrongAnswer(Owner owner, Long wordId, Instant at) {
		this.ownerType = owner.type();
		this.ownerId = owner.id();
		this.wordId = wordId;
		this.count = 1;
		this.lastAt = at;
	}

	/** 새 판에서 또 실수했다. */
	public void recordAgain(Instant at) {
		count++;
		if (at.isAfter(lastAt)) {
			lastAt = at;
		}
	}

	/** 게스트 이전: 같은 단어의 횟수는 합산하고 시각은 더 최근 값을 쓴다. */
	public void mergeFrom(WrongAnswer other) {
		count += other.count;
		if (other.lastAt.isAfter(lastAt)) {
			lastAt = other.lastAt;
		}
	}

	public void reassignTo(Owner owner) {
		this.ownerType = owner.type();
		this.ownerId = owner.id();
	}

	public Long getId() {
		return id;
	}

	public OwnerType getOwnerType() {
		return ownerType;
	}

	public String getOwnerId() {
		return ownerId;
	}

	public Long getWordId() {
		return wordId;
	}

	public int getCount() {
		return count;
	}

	public Instant getLastAt() {
		return lastAt;
	}
}
