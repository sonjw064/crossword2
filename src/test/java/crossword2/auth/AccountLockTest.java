package crossword2.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import crossword2.auth.AuthTestClient.Tokens;
import crossword2.common.ApiException;
import crossword2.feedback.FeedbackDtos.CreateFeedbackRequest;
import crossword2.feedback.FeedbackService;
import crossword2.feedback.FeedbackType;
import crossword2.puzzle.PlayService;
import crossword2.puzzle.PuzzleRepository;

/**
 * 새 기록(풀이 세션, 문의)을 만드는 서비스는 계정을 잠근 뒤 쓸 수 있는 계정인지 다시 확인한다.
 * JWT 검증을 거치지 않고 서비스를 직접 호출해, 토큰이 이미 통과한 뒤 이전이 끝난 경우(경쟁)와 같은 상황을 결정적으로 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountLockTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	PlayService playService;

	@Autowired
	FeedbackService feedbackService;

	@Autowired
	PuzzleRepository puzzles;

	private Long puzzleId() {
		return puzzles.findAll(PageRequest.of(0, 1)).getContent().get(0).getId();
	}

	private Owner migratedGuest() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "이전된손님");
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", guest.bearer())
				.content("{\"email\":\"al" + UUID.randomUUID().toString().substring(0, 8)
						+ "@example.com\",\"password\":\"secret123\",\"nickname\":\"이전회원\",\"guestId\":\"" + guest.ownerId() + "\"}"))
				.andExpect(status().isCreated());
		return new Owner(OwnerType.GUEST, guest.ownerId());
	}

	private static void assertUnauthorized(Throwable thrown, String code) {
		assertThat(thrown).isInstanceOfSatisfying(ApiException.class, e -> {
			assertThat(e.status().value()).isEqualTo(401);
			assertThat(e.code()).isEqualTo(code);
		});
	}

	// ---- 풀이 세션 시작 ----

	@Test
	void aMigratedGuestCannotStartANewSession() throws Exception {
		Owner guest = migratedGuest();

		assertThatThrownBy(() -> playService.start(puzzleId(), guest)).satisfies(t -> assertUnauthorized(t, "GUEST_MIGRATED"));
	}

	@Test
	void unknownAccountsCannotStartASession() {
		assertThatThrownBy(() -> playService.start(puzzleId(), new Owner(OwnerType.GUEST, UUID.randomUUID().toString())))
				.satisfies(t -> assertUnauthorized(t, "ACCOUNT_NOT_FOUND"));
		assertThatThrownBy(() -> playService.start(puzzleId(), new Owner(OwnerType.MEMBER, "987654321")))
				.satisfies(t -> assertUnauthorized(t, "ACCOUNT_NOT_FOUND"));
		assertThatThrownBy(() -> playService.start(puzzleId(), new Owner(OwnerType.GUEST, "not-a-uuid")))
				.satisfies(t -> assertUnauthorized(t, "ACCOUNT_NOT_FOUND"));
	}

	@Test
	void activeAccountsAndAnonymousCallersCanStillStart() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "활동손님");
		Tokens member = AuthTestClient.signup(mvc, "al" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
				"secret123", "활동회원");

		assertThat(playService.start(puzzleId(), new Owner(OwnerType.GUEST, guest.ownerId())).sessionId()).isNotNull();
		assertThat(playService.start(puzzleId(), new Owner(OwnerType.MEMBER, member.ownerId())).sessionId()).isNotNull();
		assertThat(playService.start(puzzleId(), null).sessionId()).isNotNull();
	}

	// ---- 문의 작성 ----

	@Test
	void aMigratedGuestCannotSubmitFeedbackEither() throws Exception {
		Owner guest = migratedGuest();
		CreateFeedbackRequest request = new CreateFeedbackRequest(FeedbackType.GENERAL, "제목", "내용", null, null, null, null);

		assertThatThrownBy(() -> feedbackService.create(guest, request, null, "UA", "127.0.0.1"))
				.satisfies(t -> assertUnauthorized(t, "GUEST_MIGRATED"));
	}
}
