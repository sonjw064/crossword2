package crossword2.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import crossword2.auth.AuthTestClient;
import crossword2.auth.AuthTestClient.Tokens;
import crossword2.auth.MutableClock;

/** 게스트가 쓴 문의는 90일 뒤 익명화된다(작성자·기기 정보·첨부 삭제, 내용은 유지). 시간을 움직여 경계를 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(GuestFeedbackRetentionTest.ClockConfig.class)
class GuestFeedbackRetentionTest {

	@TestConfiguration
	static class ClockConfig {

		@Bean
		@Primary
		MutableClock retentionClock() {
			return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
		}
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	Clock clock;

	@Autowired
	GuestFeedbackRetention retention;

	@Autowired
	FeedbackRepository feedbacks;

	@Autowired
	FeedbackAttachmentRepository attachments;

	@Autowired
	AttachmentStore store;

	private MutableClock clock() {
		return (MutableClock) clock;
	}

	private long submit(Tokens caller, String title, boolean withScreenshot) throws Exception {
		var request = multipart("/api/feedback")
				.file(new MockMultipartFile("data", "", "application/json",
						("{\"type\":\"BUG\",\"title\":\"" + title + "\",\"content\":\"내용 " + title + "\"}")
								.getBytes(StandardCharsets.UTF_8)));
		if (withScreenshot) {
			request.file(new MockMultipartFile("screenshot", "a.png", "image/png", TestImages.png(30, 30)));
		}
		request.header("Authorization", caller.bearer()).header("User-Agent", "TestAgent/1");
		String body = mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private Tokens freshGuest() throws Exception {
		return AuthTestClient.guest(mvc, "보관손님");
	}

	@Test
	void staleGuestFeedbackIsAnonymizedButItsTextIsKept() throws Exception {
		Tokens guest = freshGuest();
		long id = submit(guest, "오래된 문의 " + UUID.randomUUID(), true);
		String storedName = attachments.findByFeedbackId(id).orElseThrow().getStoredName();
		assertThat(store.exists(storedName)).isTrue();
		String title = feedbacks.findById(id).orElseThrow().getTitle();

		clock().advance(Duration.ofDays(90).plusSeconds(1));
		int count = retention.anonymizeStaleGuestFeedback();

		assertThat(count).isGreaterThanOrEqualTo(1);
		Feedback after = feedbacks.findById(id).orElseThrow();
		assertThat(after.getAuthorId()).isNull();
		assertThat(after.getDeviceInfo()).isNull();
		assertThat(after.getAnonymizedAt()).isEqualTo(clock().instant());
		assertThat(after.getTitle()).isEqualTo(title);
		assertThat(after.getContent()).contains("내용");
		assertThat(after.getAuthorType()).isEqualTo(crossword2.auth.OwnerType.GUEST);
		assertThat(attachments.findByFeedbackId(id)).isEmpty();
		assertThat(store.exists(storedName)).as("첨부 파일도 삭제된다").isFalse();
	}

	@Test
	void theBoundaryIsExactlyNinetyDays() throws Exception {
		Tokens guest = freshGuest();
		long id = submit(guest, "경계 문의 " + UUID.randomUUID(), false);

		clock().advance(Duration.ofDays(90));
		retention.anonymizeStaleGuestFeedback();
		assertThat(feedbacks.findById(id).orElseThrow().getAuthorId()).as("정확히 90일째까지는 유지").isEqualTo(guest.ownerId());

		clock().advance(Duration.ofSeconds(1));
		retention.anonymizeStaleGuestFeedback();
		assertThat(feedbacks.findById(id).orElseThrow().getAuthorId()).isNull();
	}

	@Test
	void youngGuestFeedbackAndMemberFeedbackAreLeftAlone() throws Exception {
		Tokens oldGuest = freshGuest();
		long oldId = submit(oldGuest, "옛 문의 " + UUID.randomUUID(), false);
		Tokens member = AuthTestClient.signup(mvc, "ret" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
				"secret123", "보관회원");
		long memberId = submit(member, "회원 문의 " + UUID.randomUUID(), true);

		clock().advance(Duration.ofDays(60));
		Tokens youngGuest = freshGuest();
		long youngId = submit(youngGuest, "새 문의 " + UUID.randomUUID(), true);
		clock().advance(Duration.ofDays(31)); // 옛 문의 91일, 새 문의 31일, 회원 문의 91일

		retention.anonymizeStaleGuestFeedback();

		assertThat(feedbacks.findById(oldId).orElseThrow().getAuthorId()).isNull();
		assertThat(feedbacks.findById(youngId).orElseThrow().getAuthorId()).isEqualTo(youngGuest.ownerId());
		Feedback memberFeedback = feedbacks.findById(memberId).orElseThrow();
		assertThat(memberFeedback.getAuthorId()).as("회원의 문의는 기간과 무관하게 유지").isEqualTo(member.ownerId());
		assertThat(memberFeedback.getDeviceInfo()).isNotNull();
		assertThat(attachments.findByFeedbackId(memberId)).isPresent();
		assertThat(attachments.findByFeedbackId(youngId)).isPresent();
	}

	@Test
	void runningItAgainChangesNothing() throws Exception {
		Tokens guest = freshGuest();
		long id = submit(guest, "멱등 문의 " + UUID.randomUUID(), true);
		clock().advance(Duration.ofDays(91));

		retention.anonymizeStaleGuestFeedback();
		Instant firstAnonymizedAt = feedbacks.findById(id).orElseThrow().getAnonymizedAt();
		clock().advance(Duration.ofDays(5));
		int second = retention.anonymizeStaleGuestFeedback();

		assertThat(second).isZero();
		assertThat(feedbacks.findById(id).orElseThrow().getAnonymizedAt()).isEqualTo(firstAnonymizedAt);
	}

	@Test
	void anAnonymizedFeedbackCanNoLongerBeClaimedByAnyone() throws Exception {
		Tokens guest = freshGuest();
		long id = submit(guest, "주인 없는 문의 " + UUID.randomUUID(), true);
		clock().advance(Duration.ofDays(91));
		retention.anonymizeStaleGuestFeedback();

		Feedback after = feedbacks.findById(id).orElseThrow();
		assertThat(after.isOwnedBy(new crossword2.auth.Owner(crossword2.auth.OwnerType.GUEST, guest.ownerId()))).isFalse();
	}
}
