package crossword2.feedback;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** 관리자의 답변. 작성 API는 4단계(관리자)에서 만든다. 회원 문의에만 달 수 있다. */
@Entity
@Table(name = "feedback_replies", indexes = @Index(name = "idx_feedback_replies_feedback", columnList = "feedback_id"))
public class FeedbackReply {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long feedbackId;

	@Column(nullable = false)
	private Long adminId;

	@Column(nullable = false, length = 2000)
	private String content;

	@Column(nullable = false)
	private Instant createdAt;

	/** 작성자가 처음 열람한 시각. null이면 읽지 않음. */
	private Instant readAt;

	protected FeedbackReply() {
	}

	public FeedbackReply(Long feedbackId, Long adminId, String content, Instant createdAt) {
		this.feedbackId = feedbackId;
		this.adminId = adminId;
		this.content = content;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public Long getFeedbackId() {
		return feedbackId;
	}

	public Long getAdminId() {
		return adminId;
	}

	public String getContent() {
		return content;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getReadAt() {
		return readAt;
	}
}
