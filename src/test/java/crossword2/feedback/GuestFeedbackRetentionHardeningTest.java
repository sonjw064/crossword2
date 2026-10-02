package crossword2.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import crossword2.auth.AuthTestClient;
import crossword2.auth.AuthTestClient.Tokens;
import crossword2.auth.Owner;
import crossword2.auth.OwnerType;

/** 익명화의 묶음 처리와 게스트 이전과의 경쟁(조건부 UPDATE). 오래된 문의는 저장소로 직접 만들어 시간을 흉내 내지 않는다. */
@SpringBootTest(properties = "app.feedback.retention-batch-size=2")
@AutoConfigureMockMvc
class GuestFeedbackRetentionHardeningTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	GuestFeedbackRetention retention;

	@Autowired
	FeedbackRepository feedbacks;

	@Autowired
	FeedbackAttachmentRepository attachments;

	@Autowired
	AttachmentStore store;

	private static final Instant LONG_AGO = Instant.now().minus(Duration.ofDays(120));

	private Feedback staleFeedback(Owner owner, boolean withAttachment) {
		Feedback f = feedbacks.save(new Feedback(FeedbackType.BUG, "오래된 문의 " + UUID.randomUUID(), "내용", owner, null,
				null, null, "TestAgent/1 | screen=800x600", LONG_AGO));
		if (withAttachment) {
			String name = store.save(TestImages.png(10, 10), "png");
			attachments.save(new FeedbackAttachment(f.getId(), name, "image/png", 100));
		}
		return f;
	}

	private Owner guest(String nickname) throws Exception {
		return new Owner(OwnerType.GUEST, AuthTestClient.guest(mvc, nickname).ownerId());
	}

	@Test
	void manyStaleRowsAreProcessedInSmallBatchesAndFullyCleaned() throws Exception {
		Owner owner = guest("대량손님");
		List<Feedback> stale = new ArrayList<>();
		List<String> files = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			Feedback f = staleFeedback(owner, i % 2 == 0);
			stale.add(f);
			attachments.findByFeedbackId(f.getId()).ifPresent(a -> files.add(a.getStoredName()));
		}
		assertThat(files).isNotEmpty().allSatisfy(n -> assertThat(store.exists(n)).isTrue());

		int count = retention.anonymizeStaleGuestFeedback();

		assertThat(count).as("배치 크기(2)보다 많은 7건을 모두 처리").isGreaterThanOrEqualTo(7);
		for (Feedback f : stale) {
			Feedback after = feedbacks.findById(f.getId()).orElseThrow();
			assertThat(after.getAuthorId()).isNull();
			assertThat(after.getDeviceInfo()).isNull();
			assertThat(after.getTitle()).isEqualTo(f.getTitle());
			assertThat(attachments.findByFeedbackId(f.getId())).isEmpty();
		}
		assertThat(files).allSatisfy(n -> assertThat(store.exists(n)).isFalse());
		assertThat(retention.anonymizeStaleGuestFeedback()).as("멱등").isZero();
	}

	@Test
	@Transactional
	void theConditionalUpdateSkipsRowsThatAreNoLongerGuestOwned() throws Exception {
		Owner guest = guest("조건부손님");
		Owner member = new Owner(OwnerType.MEMBER, "424242");
		Feedback f = staleFeedback(guest, true);
		Instant cutoff = Instant.now().minus(Duration.ofDays(90));
		List<Long> ids = feedbacks.findStaleGuestFeedbackIds(OwnerType.GUEST, cutoff, PageRequest.of(0, 1000));
		assertThat(ids).contains(f.getId());

		// 익명화 대상을 조회한 뒤, 실행 전에 게스트 이전이 이 문의를 회원 소유로 바꿨다고 가정한다
		feedbacks.reassignAuthor(guest.type(), guest.id(), member.type(), member.id());
		int updated = feedbacks.anonymizeStillStale(ids, OwnerType.GUEST, cutoff, Instant.now());

		Feedback after = feedbacks.findById(f.getId()).orElseThrow();
		assertThat(after.getAuthorType()).isEqualTo(OwnerType.MEMBER);
		assertThat(after.getAuthorId()).as("회원 문의는 익명화되지 않는다").isEqualTo("424242");
		assertThat(after.getDeviceInfo()).isNotNull();
		assertThat(after.getAnonymizedAt()).isNull();
		assertThat(updated).isZero();
	}

	// ---- 경쟁: 익명화 중에 게스트가 회원으로 이전된다 ----

	private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		try {
			CountDownLatch ready = new CountDownLatch(tasks.size());
			CountDownLatch go = new CountDownLatch(1);
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> task : tasks) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return task.call();
				}));
			}
			ready.await();
			go.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> f : futures) {
				results.add(f.get());
			}
			return results;
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void aMemberKeepsTheirFeedbackIfTheMigrationRacesWithTheRetention() throws Exception {
		for (int round = 0; round < 8; round++) {
			Tokens guestTokens = AuthTestClient.guest(mvc, "경쟁손님" + round);
			Owner guest = new Owner(OwnerType.GUEST, guestTokens.ownerId());
			List<Feedback> rows = new ArrayList<>();
			for (int i = 0; i < 5; i++) {
				rows.add(staleFeedback(guest, i == 0));
			}
			String email = "rr" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";

			List<MockHttpServletResponse> responses = runTogether(List.of(
					() -> {
						retention.anonymizeStaleGuestFeedback();
						return null;
					},
					() -> mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
							.header("Authorization", guestTokens.bearer())
							.content("{\"email\":\"" + email + "\",\"password\":\"secret123\",\"nickname\":\"경쟁회원\",\"guestId\":\""
									+ guestTokens.ownerId() + "\"}")).andReturn().getResponse()));

			MockHttpServletResponse signup = responses.get(1);
			assertThat(signup.getStatus()).as("round %d signup: %s", round, signup.getContentAsString()).isEqualTo(201);
			String memberId = JsonPath.read(signup.getContentAsString(), "$.ownerId");
			for (Feedback f : rows) {
				Feedback after = feedbacks.findById(f.getId()).orElseThrow();
				boolean movedToMember = after.getAuthorType() == OwnerType.MEMBER && memberId.equals(after.getAuthorId())
						&& after.getDeviceInfo() != null && after.getAnonymizedAt() == null;
				boolean anonymizedFirst = after.getAuthorId() == null && after.getDeviceInfo() == null
						&& after.getAnonymizedAt() != null;
				assertThat(movedToMember || anonymizedFirst)
						.as("round %d feedback %d: 회원에게 이전되었거나(온전히) 이전 전에 익명화되었거나, 둘 중 하나여야 한다: %s/%s/%s",
								round, f.getId(), after.getAuthorType(), after.getAuthorId(), after.getAnonymizedAt())
						.isTrue();
			}
		}
	}
}
