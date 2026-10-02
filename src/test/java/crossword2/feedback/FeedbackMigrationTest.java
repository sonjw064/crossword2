package crossword2.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import crossword2.auth.AuthTestClient;
import crossword2.auth.AuthTestClient.Tokens;
import crossword2.auth.GuestAccountRepository;
import crossword2.auth.GuestDataMigrator;
import crossword2.auth.Owner;
import crossword2.auth.OwnerType;
import crossword2.auth.UserRepository;

/** 게스트가 회원이 되면 그 게스트의 문의가 회원 소유가 되어 이후 답변을 받을 수 있다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FeedbackMigrationTest.Config.class)
class FeedbackMigrationTest {

	static class SwitchableFailure implements GuestDataMigrator {

		volatile boolean fail;

		@Override
		public void migrate(Owner guest, Owner member) {
			if (fail) {
				throw new IllegalStateException("boom");
			}
		}
	}

	@TestConfiguration
	static class Config {

		@Bean
		SwitchableFailure feedbackSwitchableFailure() {
			return new SwitchableFailure();
		}
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	FeedbackRepository feedbacks;

	@Autowired
	FeedbackReplyRepository replies;

	@Autowired
	UserRepository users;

	@Autowired
	GuestAccountRepository guests;

	@Autowired
	SwitchableFailure failure;

	@AfterEach
	void reset() {
		failure.fail = false;
	}

	private static String uniqueEmail() {
		return "fm" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
	}

	private long submit(Tokens caller, String title) throws Exception {
		String body = mvc.perform(post("/api/feedback").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", caller.bearer())
				.content("{\"type\":\"GENERAL\",\"title\":\"" + title + "\",\"content\":\"내용\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private String signupWithGuest(String email, Tokens guest) throws Exception {
		return mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", guest.bearer())
				.content("{\"email\":\"" + email + "\",\"password\":\"secret123\",\"nickname\":\"이전회원\",\"guestId\":\""
						+ guest.ownerId() + "\"}")).andExpect(status().isCreated()).andReturn().getResponse()
				.getContentAsString();
	}

	@Test
	void theGuestsFeedbackBelongsToTheMemberAfterSignupAndRepliesBecomePossible() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "문의이전손님");
		long first = submit(guest, "첫 문의");
		long second = submit(guest, "둘째 문의");
		Tokens bystander = AuthTestClient.guest(mvc, "지나가는손님");
		long others = submit(bystander, "남의 문의");

		String signup = signupWithGuest(uniqueEmail(), guest);
		String memberId = JsonPath.read(signup, "$.ownerId");
		String memberToken = JsonPath.read(signup, "$.accessToken");

		for (long id : List.of(first, second)) {
			Feedback moved = feedbacks.findById(id).orElseThrow();
			assertThat(moved.getAuthorType()).isEqualTo(OwnerType.MEMBER);
			assertThat(moved.getAuthorId()).isEqualTo(memberId);
		}
		assertThat(feedbacks.findById(others).orElseThrow().getAuthorId()).isEqualTo(bystander.ownerId());

		// 이전 후에는 답변을 받아 볼 수 있다
		replies.save(new FeedbackReply(first, 1L, "답변 드립니다", Instant.now()));
		String mine = mvc.perform(get("/api/feedback/mine").header("Authorization", "Bearer " + memberToken))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(mine, "$.items[*].title")).containsExactlyInAnyOrder("첫 문의", "둘째 문의");
		assertThat(mine).contains("답변 드립니다");
		assertThat(((Number) JsonPath.read(mine, "$.unreadCount")).intValue()).isEqualTo(1);
	}

	@Test
	void loggingInToAnExistingAccountWithAGuestAlsoMovesTheFeedback() throws Exception {
		String email = uniqueEmail();
		Tokens member = AuthTestClient.signup(mvc, email, "secret123", "기존회원");
		long memberOwn = submit(member, "원래 있던 회원 문의");
		Tokens guest = AuthTestClient.guest(mvc, "로그인이전손님");
		long guestOwn = submit(guest, "게스트 문의");

		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", guest.bearer())
				.content("{\"email\":\"" + email + "\",\"password\":\"secret123\",\"guestId\":\"" + guest.ownerId() + "\"}"))
				.andExpect(status().isOk());

		assertThat(feedbacks.findById(guestOwn).orElseThrow().getAuthorId()).isEqualTo(member.ownerId());
		assertThat(feedbacks.findById(memberOwn).orElseThrow().getAuthorId()).isEqualTo(member.ownerId());
		String mine = mvc.perform(get("/api/feedback/mine").header("Authorization", member.bearer()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(mine, "$.items[*].title")).containsExactlyInAnyOrder("원래 있던 회원 문의", "게스트 문의");
	}

	@Test
	void aMigratedGuestCanNoLongerSubmitNewFeedbackWithTheOldToken() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "이전뒤손님");
		signupWithGuest(uniqueEmail(), guest);

		mvc.perform(post("/api/feedback").contentType(MediaType.APPLICATION_JSON).header("Authorization", guest.bearer())
				.content("{\"type\":\"GENERAL\",\"title\":\"늦은 문의\",\"content\":\"내용\"}"))
				.andExpect(status().isUnauthorized());
		assertThat(feedbacks.findByAuthorTypeAndAuthorIdOrderByCreatedAtDescIdDesc(OwnerType.GUEST, guest.ownerId())).isEmpty();
	}

	@Test
	void aFailureElsewhereCancelsTheFeedbackMoveAndTheSignup() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "롤백문의손님");
		long id = submit(guest, "롤백 문의");
		String email = uniqueEmail();
		failure.fail = true;

		assertThatThrownBy(() -> mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", guest.bearer())
				.content("{\"email\":\"" + email + "\",\"password\":\"secret123\",\"nickname\":\"롤백\",\"guestId\":\""
						+ guest.ownerId() + "\"}"))).hasRootCauseInstanceOf(IllegalStateException.class);

		assertThat(users.existsByEmail(email)).isFalse();
		assertThat(feedbacks.findById(id).orElseThrow().getAuthorId()).isEqualTo(guest.ownerId());
		assertThat(guests.findById(UUID.fromString(guest.ownerId())).orElseThrow().getMigratedToUserId()).isNull();
	}

	// ---- 경쟁: 문의를 쓰는 중에 이전이 일어난다 ----

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
	void submittingWhileTheGuestIsBeingMigratedNeverLeavesAnOrphanedFeedback() throws Exception {
		for (int round = 0; round < 8; round++) {
			Tokens guest = AuthTestClient.guest(mvc, "경쟁문의" + round);
			submit(guest, "이전 전 문의");
			String email = uniqueEmail();

			List<MockHttpServletResponse> responses = runTogether(List.of(
					() -> mvc.perform(post("/api/feedback").contentType(MediaType.APPLICATION_JSON)
							.header("Authorization", guest.bearer())
							.content("{\"type\":\"GENERAL\",\"title\":\"경쟁 중 문의\",\"content\":\"내용\"}")).andReturn().getResponse(),
					() -> mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
							.header("Authorization", guest.bearer())
							.content("{\"email\":\"" + email + "\",\"password\":\"secret123\",\"nickname\":\"경쟁회원\",\"guestId\":\""
									+ guest.ownerId() + "\"}")).andReturn().getResponse()));

			MockHttpServletResponse submitted = responses.get(0);
			MockHttpServletResponse signup = responses.get(1);
			assertThat(signup.getStatus()).as("round %d signup: %s", round, signup.getContentAsString()).isEqualTo(201);
			assertThat(submitted.getStatus()).as("round %d submit: %s", round, submitted.getContentAsString()).isIn(201, 401);
			assertThat(feedbacks.findByAuthorTypeAndAuthorIdOrderByCreatedAtDescIdDesc(OwnerType.GUEST, guest.ownerId()))
					.as("round %d: 이전 뒤에 게스트 소유로 남은 문의가 없어야 한다", round).isEmpty();
			String memberId = JsonPath.read(signup.getContentAsString(), "$.ownerId");
			int expected = submitted.getStatus() == 201 ? 2 : 1;
			assertThat(feedbacks.findByAuthorTypeAndAuthorIdOrderByCreatedAtDescIdDesc(OwnerType.MEMBER, memberId))
					.hasSize(expected);
		}
	}
}
