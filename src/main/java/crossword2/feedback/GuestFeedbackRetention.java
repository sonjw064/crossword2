package crossword2.feedback;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import crossword2.auth.OwnerType;

/**
 * 게스트가 쓴 오래된 문의(기본 90일)를 익명화한다(SPEC 4.5). 작성자 ID와 기기 정보를 지우고 첨부는 삭제 대기로 표시하며,
 * 제목/내용은 통계를 위해 남긴다. 회원으로 이전된 문의와 기간 안의 문의는 건드리지 않고, 여러 번 실행해도 결과가 같다.
 *
 * <ul>
 * <li>경쟁: 읽어 둔 엔티티를 고쳐 쓰지 않고 "아직 게스트 소유이고 기간이 지난 행만" 바꾸는 조건부 일괄 UPDATE를 쓴다.
 *     게스트 이전이 같은 행을 먼저(또는 동시에) 회원 소유로 바꾸면 조건에 맞지 않아 건너뛴다.</li>
 * <li>규모: 정해진 크기의 묶음(batch)마다 따로 커밋해, 대량이 쌓여도 메모리와 트랜잭션 시간이 일정하다.</li>
 * <li>파일: 첨부 행은 파일이 실제로 지워진 뒤에만 삭제한다. 실패한 파일은 행이 남아 다음 실행에서 다시 시도되고,
 *     한 파일의 실패가 다른 파일 삭제를 막지 않는다.</li>
 * </ul>
 */
@Component
public class GuestFeedbackRetention {

	private static final Logger log = LoggerFactory.getLogger(GuestFeedbackRetention.class);
	private static final int MAX_BATCHES = 100_000; // 무한 반복 방지용 안전장치

	private final FeedbackRepository feedbacks;
	private final FeedbackAttachmentRepository attachments;
	private final AttachmentStore store;
	private final FeedbackProperties props;
	private final Clock clock;
	private final TransactionTemplate tx;

	public GuestFeedbackRetention(FeedbackRepository feedbacks, FeedbackAttachmentRepository attachments,
			AttachmentStore store, FeedbackProperties props, Clock clock, PlatformTransactionManager transactionManager) {
		this.feedbacks = feedbacks;
		this.attachments = attachments;
		this.store = store;
		this.props = props;
		this.clock = clock;
		this.tx = new TransactionTemplate(transactionManager);
	}

	@Scheduled(cron = "0 40 3 * * *")
	public void scheduledAnonymize() {
		int count = anonymizeStaleGuestFeedback();
		if (count > 0) {
			log.info("Anonymized {} guest feedback entries", count);
		}
	}

	/** @return 이번에 익명화한 문의 수 (끝에 삭제 대기 중인 첨부 파일도 정리한다) */
	public int anonymizeStaleGuestFeedback() {
		Instant now = clock.instant();
		Instant cutoff = now.minus(props.guestRetention());
		int anonymized = 0;
		for (int i = 0; i < MAX_BATCHES; i++) {
			int[] batch = tx.execute(status -> anonymizeBatch(cutoff, now));
			if (batch == null || batch[0] == 0) {
				break; // 더 이상 대상이 없다
			}
			anonymized += batch[1];
		}
		purgePendingAttachments();
		return anonymized;
	}

	/** @return {조회한 수, 실제로 익명화한 수} */
	private int[] anonymizeBatch(Instant cutoff, Instant now) {
		List<Long> ids = feedbacks.findStaleGuestFeedbackIds(OwnerType.GUEST, cutoff,
				PageRequest.of(0, Math.max(1, props.retentionBatchSize())));
		if (ids.isEmpty()) {
			return new int[] { 0, 0 };
		}
		int updated = feedbacks.anonymizeStillStale(ids, OwnerType.GUEST, cutoff, now);
		if (updated > 0) {
			List<Long> anonymizedIds = feedbacks.findAnonymizedIds(ids);
			attachments.findByFeedbackIdIn(anonymizedIds).forEach(a -> a.requestDeletion(now));
		}
		return new int[] { ids.size(), updated };
	}

	/**
	 * 삭제 대기로 표시된 첨부의 파일을 지우고 행을 삭제한다. 개별 실패는 기록만 하고 계속하며, 실패한 행은 남겨 다음에 다시 시도한다.
	 *
	 * @return 이번에 완전히 정리한 첨부 수
	 */
	public int purgePendingAttachments() {
		int purged = 0;
		long lastId = 0;
		while (true) {
			long after = lastId;
			List<FeedbackAttachment> pending = tx.execute(status -> attachments
					.findByDeleteRequestedAtIsNotNullAndIdGreaterThanOrderByIdAsc(after,
							PageRequest.of(0, Math.max(1, props.retentionBatchSize()))));
			if (pending == null || pending.isEmpty()) {
				return purged;
			}
			for (FeedbackAttachment attachment : pending) {
				lastId = Math.max(lastId, attachment.getId());
				try {
					store.delete(attachment.getStoredName());
					tx.executeWithoutResult(status -> attachments.deleteById(attachment.getId()));
					purged++;
				} catch (RuntimeException e) {
					log.warn("Could not delete attachment file {}, will retry next run", attachment.getStoredName(), e);
				}
			}
		}
	}
}
