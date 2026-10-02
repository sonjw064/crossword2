package crossword2.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import crossword2.auth.AuthTestClient;
import crossword2.auth.AuthTestClient.Tokens;
import crossword2.auth.Owner;
import crossword2.auth.OwnerType;
import crossword2.puzzle.Puzzle;
import crossword2.puzzle.PuzzleEntry;
import crossword2.puzzle.PuzzleRepository;

/** 같은 사용자의 여러 풀이가 동시에 끝나거나, 끝나는 중에 게스트 이전이 일어나도 기록이 어긋나지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
class ProgressConcurrencyTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	PuzzleRepository puzzleRepository;

	@Autowired
	PlayRecordRepository records;

	@Autowired
	WrongAnswerRepository wrongAnswers;

	@Autowired
	crossword2.puzzle.PlaySessionRepository sessions;

	PlayTestHelper play;
	Puzzle puzzle;
	List<PuzzleEntry> entries;

	@BeforeEach
	void setUp() {
		play = new PlayTestHelper(mvc, puzzleRepository);
		puzzle = play.puzzle();
		entries = puzzle.getEntries();
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

	@Test
	void twoPlaysOfTheSameUserFinishingTogetherBothCountOnTheSameWord() throws Exception {
		for (int round = 0; round < 5; round++) {
			Tokens member = AuthTestClient.signup(mvc, "cc" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
					"secret123", "동시회원");
			PuzzleEntry weak = entries.get(0);
			UUID s1 = play.start(puzzle, member);
			UUID s2 = play.start(puzzle, member);
			play.check(puzzle, s1, member, List.of(weak), false);
			play.check(puzzle, s2, member, List.of(weak), false);

			List<MockHttpServletResponse> responses = runTogether(List.of(
					() -> play.solve(puzzle, s1, member, entries).andReturn().getResponse(),
					() -> play.solve(puzzle, s2, member, entries).andReturn().getResponse()));

			assertThat(responses).as("round %d", round).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(200));
			Owner owner = new Owner(OwnerType.MEMBER, member.ownerId());
			assertThat(wrongAnswers.findByOwnerTypeAndOwnerId(owner.type(), owner.id())).singleElement()
					.satisfies(w -> assertThat(w.getCount()).isEqualTo(2));
			assertThat(records.findByOwnerTypeAndOwnerId(owner.type(), owner.id(), Pageable.unpaged()).getTotalElements())
					.isEqualTo(2);
		}
	}

	@Test
	void startingASessionWhileTheGuestIsBeingMigratedNeverLeavesAGuestOwnedSession() throws Exception {
		for (int round = 0; round < 8; round++) {
			Tokens guest = AuthTestClient.guest(mvc, "시작경쟁" + round);
			String email = "st" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";

			List<MockHttpServletResponse> responses = runTogether(List.of(
					() -> mvc.perform(post("/api/puzzles/{id}/start", puzzle.getId()).header("Authorization", guest.bearer()))
							.andReturn().getResponse(),
					() -> mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
							.header("Authorization", guest.bearer())
							.content("{\"email\":\"" + email + "\",\"password\":\"secret123\",\"nickname\":\"시작경쟁\",\"guestId\":\""
									+ guest.ownerId() + "\"}")).andReturn().getResponse()));

			MockHttpServletResponse start = responses.get(0);
			MockHttpServletResponse signup = responses.get(1);
			assertThat(signup.getStatus()).as("round %d signup: %s", round, signup.getContentAsString()).isEqualTo(201);
			assertThat(start.getStatus()).as("round %d start: %s", round, start.getContentAsString()).isIn(200, 401);
			Owner guestOwner = new Owner(OwnerType.GUEST, guest.ownerId());
			assertThat(sessions.findAll().stream().filter(s -> s.isOwnedBy(guestOwner)))
					.as("round %d: 이전 뒤에 게스트 소유로 남은 세션이 없어야 한다", round).isEmpty();
			if (start.getStatus() == 200) {
				String memberId = com.jayway.jsonpath.JsonPath.read(signup.getContentAsString(), "$.ownerId");
				UUID created = UUID.fromString(com.jayway.jsonpath.JsonPath.read(start.getContentAsString(), "$.sessionId"));
				assertThat(sessions.findById(created).orElseThrow().isOwnedBy(new Owner(OwnerType.MEMBER, memberId)))
						.as("round %d: 시작된 세션은 회원 소유여야 한다", round).isTrue();
			}
		}
	}

	@Test
	void finishingWhileTheGuestIsBeingMigratedNeverStrandsData() throws Exception {
		for (int round = 0; round < 8; round++) {
			Tokens guest = AuthTestClient.guest(mvc, "경쟁이전" + round);
			UUID session = play.start(puzzle, guest);
			PuzzleEntry weak = entries.get(0);
			play.check(puzzle, session, guest, List.of(weak), false);
			String email = "cm" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";

			List<MockHttpServletResponse> responses = runTogether(List.of(
					() -> play.solve(puzzle, session, guest, entries).andReturn().getResponse(),
					() -> mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
							.header("Authorization", guest.bearer())
							.content("{\"email\":\"" + email + "\",\"password\":\"secret123\",\"nickname\":\"이전경쟁\",\"guestId\":\""
									+ guest.ownerId() + "\"}")).andReturn().getResponse()));

			MockHttpServletResponse finish = responses.get(0);
			MockHttpServletResponse signup = responses.get(1);
			assertThat(signup.getStatus()).as("round %d signup: %s", round, signup.getContentAsString()).isEqualTo(201);
			// 종료는 이전보다 먼저(200) 또는 이전 뒤에 게스트 토큰이 거부되어(401) 끝난다. 교착/서버 오류는 없어야 한다.
			assertThat(finish.getStatus()).as("round %d finish: %s", round, finish.getContentAsString()).isIn(200, 401, 403);

			Owner g = new Owner(OwnerType.GUEST, guest.ownerId());
			assertThat(records.findByOwnerTypeAndOwnerId(g.type(), g.id(), Pageable.unpaged()).getTotalElements())
					.as("이전 뒤에 게스트 소유로 남은 기록이 없어야 한다").isZero();
			assertThat(wrongAnswers.findByOwnerTypeAndOwnerId(g.type(), g.id())).isEmpty();
			if (finish.getStatus() == 200) {
				String memberId = com.jayway.jsonpath.JsonPath.read(signup.getContentAsString(), "$.ownerId");
				assertThat(records.findByOwnerTypeAndOwnerId(OwnerType.MEMBER, memberId, Pageable.unpaged()).getTotalElements())
						.as("round %d: 끝난 풀이의 기록은 회원에게 있어야 한다", round).isEqualTo(1);
				assertThat(wrongAnswers.findByOwnerTypeAndOwnerId(OwnerType.MEMBER, memberId)).hasSize(1);
			}
		}
	}
}
