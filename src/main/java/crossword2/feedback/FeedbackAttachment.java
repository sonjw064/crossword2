package crossword2.feedback;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 스크린샷 첨부. 사용자가 지은 파일명은 저장하지 않고, 서버가 만든 임의 이름({@code storedName})만 쓴다. */
@Entity
@Table(name = "feedback_attachments")
public class FeedbackAttachment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private Long feedbackId;

	@Column(nullable = false, length = 60)
	private String storedName;

	@Column(nullable = false, length = 30)
	private String contentType;

	@Column(nullable = false)
	private long size;

	/** 파일을 지워야 한다는 표시(익명화 등). 파일이 실제로 지워진 뒤에야 행을 삭제하므로 실패해도 다시 시도할 수 있다. */
	private Instant deleteRequestedAt;

	protected FeedbackAttachment() {
	}

	public FeedbackAttachment(Long feedbackId, String storedName, String contentType, long size) {
		this.feedbackId = feedbackId;
		this.storedName = storedName;
		this.contentType = contentType;
		this.size = size;
	}

	public void requestDeletion(Instant now) {
		if (deleteRequestedAt == null) {
			deleteRequestedAt = now;
		}
	}

	public Instant getDeleteRequestedAt() {
		return deleteRequestedAt;
	}

	public Long getId() {
		return id;
	}

	public Long getFeedbackId() {
		return feedbackId;
	}

	public String getStoredName() {
		return storedName;
	}

	public String getContentType() {
		return contentType;
	}

	public long getSize() {
		return size;
	}
}
