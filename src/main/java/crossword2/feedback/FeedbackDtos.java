package crossword2.feedback;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 작성자에게 돌려주는 응답에는 영어 정답 단어를 넣지 않는다. 단어 신고는 풀이 도중에도 할 수 있어서
 * 영어 단어를 돌려주면 정답이 새기 때문이다(한국어 뜻과 wordId만 준다).
 */
public final class FeedbackDtos {

	/** 화면 크기 예: 1280x720 또는 390x844@3 */
	static final String SCREEN_FORMAT = "^\\d{2,5}x\\d{2,5}(@\\d(\\.\\d{1,2})?)?$";

	private FeedbackDtos() {
	}

	/**
	 * 제목/내용은 퍼즐 내 빠른 신고({@code wordId} 있음)일 때만 생략할 수 있다.
	 * {@code reason}과 {@code wordId}는 함께 보내야 하고 {@code wordId}는 단어 오류 신고에만 쓴다.
	 */
	public record CreateFeedbackRequest(
			@NotNull FeedbackType type,
			@Size(max = 100) String title,
			@Size(max = 2000) String content,
			Long puzzleId,
			Long wordId,
			FeedbackReason reason,
			@Pattern(regexp = SCREEN_FORMAT) String screen) {
	}

	public record FeedbackCreated(Long id, FeedbackType type, FeedbackStatus status, boolean replyAvailable,
			boolean hasAttachment, Instant createdAt) {
	}

	public record ReplyView(Long id, String content, Instant createdAt, Instant readAt, boolean unread) {
	}

	public record FeedbackView(Long id, FeedbackType type, String title, String content, FeedbackStatus status,
			Instant createdAt, Long puzzleId, Long wordId, String wordKorean, FeedbackReason reason,
			boolean hasAttachment, List<ReplyView> replies) {
	}

	public record MineResponse(List<FeedbackView> items, long unreadCount) {
	}

	public record UnreadCount(long count) {
	}

	/** 첨부 다운로드용. */
	public record AttachmentFile(byte[] bytes, String contentType) {
	}
}
