package crossword2.feedback;

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

	protected FeedbackAttachment() {
	}

	public FeedbackAttachment(Long feedbackId, String storedName, String contentType, long size) {
		this.feedbackId = feedbackId;
		this.storedName = storedName;
		this.contentType = contentType;
		this.size = size;
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
