package crossword2.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import crossword2.auth.AuthTestClient;
import crossword2.auth.Owner;
import crossword2.auth.OwnerType;

/** 첨부 파일 삭제가 실패해도 개인정보 파일이 영구히 남지 않고(다음 실행에서 재시도), 한 파일의 실패가 다른 파일을 막지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
class GuestFeedbackRetentionFailureTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	GuestFeedbackRetention retention;

	@Autowired
	FeedbackRepository feedbacks;

	@Autowired
	FeedbackAttachmentRepository attachments;

	@MockitoSpyBean
	AttachmentStore store;

	private FeedbackAttachment staleWithFile(Owner owner) {
		Feedback f = feedbacks.save(new Feedback(FeedbackType.BUG, "삭제 실패 문의 " + UUID.randomUUID(), "내용", owner, null,
				null, null, "UA", Instant.now().minus(Duration.ofDays(120))));
		String name = store.save(TestImages.png(10, 10), "png");
		return attachments.save(new FeedbackAttachment(f.getId(), name, "image/png", 100));
	}

	@Test
	void aFailedFileDeletionIsRetriedAndDoesNotBlockTheOthers() throws Exception {
		Owner owner = new Owner(OwnerType.GUEST, AuthTestClient.guest(mvc, "삭제실패손님").ownerId());
		FeedbackAttachment stuck = staleWithFile(owner);
		FeedbackAttachment fine = staleWithFile(owner);
		doThrow(new UncheckedIOException("disk error", new java.io.IOException("locked"))).when(store)
				.delete(eq(stuck.getStoredName()));

		retention.anonymizeStaleGuestFeedback();

		// 문의는 익명화되었고, 실패한 파일만 남아 있다(행도 남아 다음에 다시 시도할 수 있다)
		assertThat(feedbacks.findById(stuck.getFeedbackId()).orElseThrow().getAuthorId()).isNull();
		assertThat(feedbacks.findById(fine.getFeedbackId()).orElseThrow().getAuthorId()).isNull();
		assertThat(store.exists(stuck.getStoredName())).isTrue();
		assertThat(attachments.findByFeedbackId(stuck.getFeedbackId())).isPresent()
				.get().satisfies(a -> assertThat(a.getDeleteRequestedAt()).isNotNull());
		assertThat(store.exists(fine.getStoredName())).as("다른 파일의 삭제는 막히지 않는다").isFalse();
		assertThat(attachments.findByFeedbackId(fine.getFeedbackId())).isEmpty();

		// 문제가 해결된 뒤의 다음 실행에서 정리된다 (문의는 이미 익명화되어 있어도)
		Mockito.reset(store);
		int purged = retention.anonymizeStaleGuestFeedback() >= 0 ? retention.purgePendingAttachments() : 0;

		assertThat(store.exists(stuck.getStoredName())).isFalse();
		assertThat(attachments.findByFeedbackId(stuck.getFeedbackId())).isEmpty();
		assertThat(purged).isGreaterThanOrEqualTo(0);
	}

	@Test
	void theNextScheduledRunAloneCleansUpLeftovers() throws Exception {
		Owner owner = new Owner(OwnerType.GUEST, AuthTestClient.guest(mvc, "재시도손님").ownerId());
		FeedbackAttachment stuck = staleWithFile(owner);
		doThrow(new UncheckedIOException("disk error", new java.io.IOException("locked"))).when(store)
				.delete(eq(stuck.getStoredName()));
		retention.anonymizeStaleGuestFeedback();
		assertThat(store.exists(stuck.getStoredName())).isTrue();

		Mockito.reset(store);
		retention.anonymizeStaleGuestFeedback(); // 새로 익명화할 문의가 없어도 삭제 대기 파일은 정리한다

		assertThat(store.exists(stuck.getStoredName())).isFalse();
		assertThat(attachments.findByFeedbackId(stuck.getFeedbackId())).isEmpty();
	}
}
