package crossword2.feedback;

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

/** 문의/피드백. 이메일은 받지 않는다. 게스트 문의는 90일 뒤 익명화되어 작성자와 기기 정보가 지워진다(authorId = null). */
@Entity
@Table(name = "feedback", indexes = @Index(name = "idx_feedback_author", columnList = "author_type, author_id, created_at"))
public class Feedback {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private FeedbackType type;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(nullable = false, length = 2000)
	private String content;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private FeedbackStatus status = FeedbackStatus.RECEIVED;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OwnerType authorType;

	/** 익명화되면 null. */
	@Column(length = 36)
	private String authorId;

	private Long puzzleId;

	private Long wordId;

	@Enumerated(EnumType.STRING)
	@Column(length = 30)
	private FeedbackReason reason;

	@Column(length = 500)
	private String deviceInfo;

	@Column(nullable = false)
	private Instant createdAt;

	private Instant anonymizedAt;

	protected Feedback() {
	}

	public Feedback(FeedbackType type, String title, String content, Owner author, Long puzzleId, Long wordId,
			FeedbackReason reason, String deviceInfo, Instant createdAt) {
		this.type = type;
		this.title = title;
		this.content = content;
		this.authorType = author.type();
		this.authorId = author.id();
		this.puzzleId = puzzleId;
		this.wordId = wordId;
		this.reason = reason;
		this.deviceInfo = deviceInfo;
		this.createdAt = createdAt;
	}

	public boolean isOwnedBy(Owner owner) {
		return authorId != null && owner != null && authorType == owner.type() && authorId.equals(owner.id());
	}

	/** 작성자와 기기 정보를 지운다. 제목/내용/유형/상태는 통계를 위해 남긴다. */
	public void anonymize(Instant now) {
		this.authorId = null;
		this.deviceInfo = null;
		this.anonymizedAt = now;
	}

	public Long getId() {
		return id;
	}

	public FeedbackType getType() {
		return type;
	}

	public String getTitle() {
		return title;
	}

	public String getContent() {
		return content;
	}

	public FeedbackStatus getStatus() {
		return status;
	}

	public OwnerType getAuthorType() {
		return authorType;
	}

	public String getAuthorId() {
		return authorId;
	}

	public Long getPuzzleId() {
		return puzzleId;
	}

	public Long getWordId() {
		return wordId;
	}

	public FeedbackReason getReason() {
		return reason;
	}

	public String getDeviceInfo() {
		return deviceInfo;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getAnonymizedAt() {
		return anonymizedAt;
	}
}
