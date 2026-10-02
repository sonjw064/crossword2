package crossword2.feedback;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import crossword2.auth.OwnerType;

/**
 * 게스트가 쓴 오래된 문의(기본 90일)를 익명화한다(SPEC 4.5). 작성자 ID와 기기 정보를 지우고 첨부 파일을 삭제하며,
 * 제목/내용은 통계를 위해 남긴다. 회원으로 이전된 문의와 기간 안의 문의는 건드리지 않고, 여러 번 실행해도 결과가 같다.
 */
@Component
public class GuestFeedbackRetention {

	private static final Logger log = LoggerFactory.getLogger(GuestFeedbackRetention.class);

	private final FeedbackRepository feedbacks;
	private final FeedbackAttachmentRepository attachments;
	private final AttachmentStore store;
	private final FeedbackProperties props;
	private final Clock clock;

	public GuestFeedbackRetention(FeedbackRepository feedbacks, FeedbackAttachmentRepository attachments,
			AttachmentStore store, FeedbackProperties props, Clock clock) {
		this.feedbacks = feedbacks;
		this.attachments = attachments;
		this.store = store;
		this.props = props;
		this.clock = clock;
	}

	@Scheduled(cron = "0 40 3 * * *")
	public void scheduledAnonymize() {
		int count = anonymizeStaleGuestFeedback();
		if (count > 0) {
			log.info("Anonymized {} guest feedback entries", count);
		}
	}

	/** @return 이번에 익명화한 문의 수 */
	@Transactional
	public int anonymizeStaleGuestFeedback() {
		Instant now = clock.instant();
		List<Feedback> stale = feedbacks.findByAuthorTypeAndAuthorIdIsNotNullAndCreatedAtBefore(OwnerType.GUEST,
				now.minus(props.guestRetention()));
		if (stale.isEmpty()) {
			return 0;
		}
		List<FeedbackAttachment> files = attachments.findByFeedbackIdIn(stale.stream().map(Feedback::getId).toList());
		attachments.deleteAll(files);
		stale.forEach(f -> f.anonymize(now));
		// 파일은 DB 변경이 커밋된 뒤에 지운다
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				files.forEach(a -> store.delete(a.getStoredName()));
			}
		});
		return stale.size();
	}
}
