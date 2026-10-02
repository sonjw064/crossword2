package crossword2.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import crossword2.auth.AuthTestClient.Tokens;
import crossword2.puzzle.PlaySession;
import crossword2.puzzle.PlaySessionRepository;
import crossword2.puzzle.Puzzle;
import crossword2.puzzle.PuzzleRepository;

@SpringBootTest
@AutoConfigureMockMvc
class GuestMigrationTest {

	private static final String PASSWORD = "secret123";

	@Autowired
	MockMvc mvc;

	@Autowired
	PuzzleRepository puzzles;

	@Autowired
	PlaySessionRepository sessions;

	@Autowired
	GuestAccountRepository guests;

	@Autowired
	UserRepository users;

	private static String email(String prefix) {
		return prefix + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
	}

	private Puzzle somePuzzle() {
		return puzzles.findAll(PageRequest.of(0, 1)).getContent().get(0);
	}

	/** 토큰이 있으면 그 주체의 세션, 없으면 익명 세션을 시작하고 세션 ID를 돌려준다. */
	private UUID startSession(Tokens owner) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/puzzles/{id}/start", somePuzzle().getId());
		if (owner != null) {
			request = request.header("Authorization", owner.bearer());
		}
		String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.sessionId"));
	}

	private boolean ownedBy(UUID session, OwnerType type, String id) {
		PlaySession s = sessions.findById(session).orElseThrow();
		return s.isOwnedBy(new Owner(type, id));
	}

	private ResultActions signup(String email, String guestId, Tokens proof) throws Exception {
		String guestPart = guestId == null ? "" : ",\"guestId\":\"" + guestId + "\"";
		MockHttpServletRequestBuilder request = post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"nickname\":\"이전\""
						+ guestPart + "}");
		if (proof != null) {
			request = request.header("Authorization", proof.bearer());
		}
		return mvc.perform(request);
	}

	private ResultActions login(String email, String password, String guestId, Tokens proof) throws Exception {
		String guestPart = guestId == null ? "" : ",\"guestId\":\"" + guestId + "\"";
		MockHttpServletRequestBuilder request = post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"" + guestPart + "}");
		if (proof != null) {
			request = request.header("Authorization", proof.bearer());
		}
		return mvc.perform(request);
	}

	private String memberId(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.ownerId");
	}

	// ---- 가입하면서 이전 ----

	@Test
	void signupWithGuestMovesTheGuestsSessionsToTheNewMember() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "이전손님");
		UUID session = startSession(guest);

		ResultActions result = signup(email("move"), guest.ownerId(), guest).andExpect(status().isCreated())
				.andExpect(jsonPath("$.guestMigrated").value(true)).andExpect(jsonPath("$.ownerType").value("MEMBER"));
		String memberId = memberId(result);

		assertThat(ownedBy(session, OwnerType.MEMBER, memberId)).isTrue();
		assertThat(ownedBy(session, OwnerType.GUEST, guest.ownerId())).isFalse();
		assertThat(guests.findById(UUID.fromString(guest.ownerId())).orElseThrow().getMigratedToUserId())
				.isEqualTo(Long.valueOf(memberId));
	}

	@Test
	void theMemberCanContinueTheMigratedSessionAndTheOldGuestTokenIsDead() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "죽은토큰");
		UUID session = startSession(guest);
		String body = signup(email("alive"), guest.ownerId(), guest).andReturn().getResponse().getContentAsString();
		String memberToken = JsonPath.read(body, "$.accessToken");
		Puzzle puzzle = puzzles.findAll(PageRequest.of(0, 1)).getContent().get(0);

		// 회원은 이어서 풀 수 있다
		mvc.perform(post("/api/puzzles/{id}/hint", puzzle.getId()).header("X-Play-Session", session.toString())
				.header("Authorization", "Bearer " + memberToken).contentType(MediaType.APPLICATION_JSON)
				.content("{\"entryId\":-1}")).andExpect(status().isBadRequest()); // 세션 검사는 통과(소유자), 항목만 잘못됨
		// 옛 게스트 토큰은 모두 거부된다
		mvc.perform(get("/api/me").header("Authorization", guest.bearer())).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/puzzles").header("Authorization", guest.bearer())).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + guest.refreshToken() + "\"}")).andExpect(status().isUnauthorized());
	}

	// ---- 로그인하면서 이전 ----

	@Test
	void anExistingMemberLoggingInWithAGuestInheritsTheGuestsSessions() throws Exception {
		String email = email("login");
		AuthTestClient.signup(mvc, email, PASSWORD, "기존회원");
		Tokens guest = AuthTestClient.guest(mvc, "새기기");
		UUID session = startSession(guest);

		ResultActions result = login(email, PASSWORD, guest.ownerId(), guest).andExpect(status().isOk())
				.andExpect(jsonPath("$.guestMigrated").value(true));

		assertThat(ownedBy(session, OwnerType.MEMBER, memberId(result))).isTrue();
	}

	@Test
	void oneMemberCanInheritSeveralGuests() throws Exception {
		String email = email("multi");
		AuthTestClient.signup(mvc, email, PASSWORD, "여러곳");
		Tokens home = AuthTestClient.guest(mvc, "집컴퓨터");
		Tokens school = AuthTestClient.guest(mvc, "학교컴퓨터");
		UUID homeSession = startSession(home);
		UUID schoolSession = startSession(school);

		String memberId = memberId(login(email, PASSWORD, home.ownerId(), home).andExpect(status().isOk()));
		login(email, PASSWORD, school.ownerId(), school).andExpect(status().isOk());

		assertThat(ownedBy(homeSession, OwnerType.MEMBER, memberId)).isTrue();
		assertThat(ownedBy(schoolSession, OwnerType.MEMBER, memberId)).isTrue();
	}

	@Test
	void otherPeoplesSessionsAreUntouched() throws Exception {
		Tokens bystander = AuthTestClient.guest(mvc, "지나가는손님");
		Tokens otherMember = AuthTestClient.signup(mvc, email("other"), PASSWORD, "다른회원");
		UUID bystanderSession = startSession(bystander);
		UUID memberSession = startSession(otherMember);
		UUID anonymousSession = startSession(null);
		Tokens guest = AuthTestClient.guest(mvc, "이사하는손님");
		startSession(guest);

		signup(email("mover"), guest.ownerId(), guest).andExpect(status().isCreated());

		assertThat(ownedBy(bystanderSession, OwnerType.GUEST, bystander.ownerId())).isTrue();
		assertThat(ownedBy(memberSession, OwnerType.MEMBER, otherMember.ownerId())).isTrue();
		assertThat(sessions.findById(anonymousSession).orElseThrow().hasOwner()).isFalse();
		mvc.perform(get("/api/me").header("Authorization", bystander.bearer())).andExpect(status().isOk());
	}

	@Test
	void withoutAGuestIdNothingIsMigratedEvenIfAGuestTokenIsSent() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "그냥가입");
		UUID session = startSession(guest);

		signup(email("plain"), null, guest).andExpect(status().isCreated())
				.andExpect(jsonPath("$.guestMigrated").value(false));

		assertThat(ownedBy(session, OwnerType.GUEST, guest.ownerId())).isTrue();
		mvc.perform(get("/api/me").header("Authorization", guest.bearer())).andExpect(status().isOk());
	}

	@Test
	void ordinarySignupReportsNoMigration() throws Exception {
		signup(email("none"), null, null).andExpect(status().isCreated()).andExpect(jsonPath("$.guestMigrated").value(false));
	}

	// ---- 소유 증명 ----

	@Test
	void aGuestIdAloneIsNotEnough() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "증명없음");
		String email = email("noproof");

		signup(email, guest.ownerId(), null).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("GUEST_PROOF_INVALID"));

		assertThat(users.existsByEmail(email)).isFalse();
		assertThat(guests.findById(UUID.fromString(guest.ownerId())).orElseThrow().getMigratedToUserId()).isNull();
	}

	@Test
	void anotherGuestsTokenCannotClaimSomeoneElsesGuestId() throws Exception {
		Tokens victim = AuthTestClient.guest(mvc, "피해자");
		Tokens attacker = AuthTestClient.guest(mvc, "공격자");
		UUID victimSession = startSession(victim);

		signup(email("steal"), victim.ownerId(), attacker).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("GUEST_PROOF_INVALID"));

		assertThat(ownedBy(victimSession, OwnerType.GUEST, victim.ownerId())).isTrue();
	}

	@Test
	void aMemberTokenIsNotAGuestProof() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "회원증명");
		Tokens member = AuthTestClient.signup(mvc, email("m"), PASSWORD, "회원");

		signup(email("fake"), guest.ownerId(), member).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("GUEST_PROOF_INVALID"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "not-a-uuid", "123", "00000000-0000-0000-0000-00000000000z" })
	void rejectsMalformedGuestIds(String guestId) throws Exception {
		signup(email("bad"), guestId, null).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	// ---- 원자성 ----

	@Test
	void aDuplicateEmailCancelsTheMigrationToo() throws Exception {
		String taken = email("taken");
		AuthTestClient.signup(mvc, taken, PASSWORD, "선점");
		Tokens guest = AuthTestClient.guest(mvc, "중복가입");
		UUID session = startSession(guest);

		signup(taken, guest.ownerId(), guest).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));

		assertThat(ownedBy(session, OwnerType.GUEST, guest.ownerId())).isTrue();
		assertThat(guests.findById(UUID.fromString(guest.ownerId())).orElseThrow().getMigratedToUserId()).isNull();
		mvc.perform(get("/api/me").header("Authorization", guest.bearer())).andExpect(status().isOk());
	}

	@Test
	void aWrongPasswordNeverMigratesAnything() throws Exception {
		String email = email("wrongpw");
		AuthTestClient.signup(mvc, email, PASSWORD, "비번틀림");
		Tokens guest = AuthTestClient.guest(mvc, "비번틀린손님");
		UUID session = startSession(guest);

		login(email, "wrongpass1", guest.ownerId(), guest).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

		assertThat(ownedBy(session, OwnerType.GUEST, guest.ownerId())).isTrue();
		assertThat(guests.findById(UUID.fromString(guest.ownerId())).orElseThrow().getMigratedToUserId()).isNull();
	}

	// ---- 한 번만, 동시성 ----

	@Test
	void concurrentMigrationsOfTheSameGuestHaveExactlyOneWinner() throws Exception {
		for (int round = 0; round < 5; round++) {
			Tokens guest = AuthTestClient.guest(mvc, "경쟁손님" + round);
			UUID session = startSession(guest);
			String emailA = email("racea");
			String emailB = email("raceb");

			List<MockHttpServletResponse> responses = runTogether(List.of(
					() -> signup(emailA, guest.ownerId(), guest).andReturn().getResponse(),
					() -> signup(emailB, guest.ownerId(), guest).andReturn().getResponse()));

			long created = responses.stream().filter(r -> r.getStatus() == 201).count();
			assertThat(created).as("round %d: %s / %s", round, responses.get(0).getContentAsString(),
					responses.get(1).getContentAsString()).isEqualTo(1);
			for (MockHttpServletResponse r : responses) {
				if (r.getStatus() != 201) {
					assertThat(r.getStatus()).isIn(401, 409); // 이미 이전됨(409) 또는 토큰이 이미 거부됨(401)
				}
			}
			MockHttpServletResponse winner = responses.stream().filter(r -> r.getStatus() == 201).findFirst().orElseThrow();
			String winnerId = JsonPath.read(winner.getContentAsString(), "$.ownerId");
			String winnerEmail = responses.get(0).getStatus() == 201 ? emailA : emailB;
			String loserEmail = winnerEmail.equals(emailA) ? emailB : emailA;
			assertThat(ownedBy(session, OwnerType.MEMBER, winnerId)).isTrue();
			assertThat(users.existsByEmail(loserEmail)).as("진 쪽의 가입은 취소되어야 한다").isFalse();
		}
	}

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
}
