package crossword2.puzzle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
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

import com.jayway.jsonpath.JsonPath;

/** 같은 풀이 세션에 병렬 요청이 와도 상태 갱신이 유실되거나 충돌하지 않는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class PlaySessionConcurrencyTest {

	private static final String SESSION = "X-Play-Session";

	@Autowired
	MockMvc mvc;

	@Autowired
	PuzzleRepository puzzleRepository;

	private Puzzle somePuzzle() {
		Long id = puzzleRepository.findAll(PageRequest.of(0, 1)).getContent().get(0).getId();
		return puzzleRepository.findWithEntriesById(id).orElseThrow();
	}

	private String startSession(Puzzle puzzle) throws Exception {
		String body = mvc.perform(post("/api/puzzles/{id}/start", puzzle.getId())).andReturn().getResponse()
				.getContentAsString();
		return JsonPath.read(body, "$.sessionId");
	}

	private MockHttpServletResponse check(Puzzle puzzle, String session, PuzzleEntry entry, String answer)
			throws Exception {
		return mvc.perform(post("/api/puzzles/{id}/check", puzzle.getId()).header(SESSION, session)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"answers\":[{\"entryId\":" + entry.getId() + ",\"answer\":\"" + answer + "\"}]}"))
				.andReturn().getResponse();
	}

	private MockHttpServletResponse reveal(Puzzle puzzle, String session) throws Exception {
		return mvc.perform(post("/api/puzzles/{id}/reveal", puzzle.getId()).header(SESSION, session)
				.contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse();
	}

	/** 모든 작업을 래치로 동시에 출발시키고 결과를 순서대로 돌려준다. */
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

	private static String wrongAnswerOf(PuzzleEntry e) {
		String w = e.getWord().getEnglish();
		return (w.charAt(0) == 'z' ? "y" : "z") + w.substring(1);
	}

	@Test
	void parallelWrongAnswersAreAllCounted() throws Exception {
		Puzzle puzzle = somePuzzle();
		PuzzleEntry entry = puzzle.getEntries().get(0);
		String session = startSession(puzzle);
		int threads = 8;

		List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			tasks.add(() -> check(puzzle, session, entry, wrongAnswerOf(entry)));
		}
		List<MockHttpServletResponse> responses = runTogether(tasks);

		assertThat(responses).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(200));
		String shortAnswer = entry.getWord().getEnglish().substring(1);
		int wrongCount = JsonPath.read(check(puzzle, session, entry, shortAnswer).getContentAsString(), "$.wrongCount");
		assertThat(wrongCount).isEqualTo(threads);
	}

	@Test
	void parallelCorrectAnswersAreAllKeptAndCompleteExactlyOnce() throws Exception {
		Puzzle puzzle = somePuzzle();
		String session = startSession(puzzle);

		List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
		for (PuzzleEntry entry : puzzle.getEntries()) {
			tasks.add(() -> check(puzzle, session, entry, entry.getWord().getEnglish()));
		}
		List<MockHttpServletResponse> responses = runTogether(tasks);

		assertThat(responses).allSatisfy(r -> assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200));
		long completedResponses = responses.stream().filter(r -> {
			try {
				return JsonPath.<Boolean>read(r.getContentAsString(), "$.completed");
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}).count();
		assertThat(completedResponses).as("exactly one request observes the completion").isEqualTo(1);
		assertThat(check(puzzle, session, puzzle.getEntries().get(0), "a").getStatus()).isEqualTo(409);
	}

	@Test
	void finalAnswerAndRevealRaceHasExactlyOneWinner() throws Exception {
		for (int round = 0; round < 5; round++) {
			Puzzle puzzle = somePuzzle();
			String session = startSession(puzzle);
			List<PuzzleEntry> entries = puzzle.getEntries();
			for (PuzzleEntry e : entries.subList(0, entries.size() - 1)) {
				assertThat(check(puzzle, session, e, e.getWord().getEnglish()).getStatus()).isEqualTo(200);
			}
			PuzzleEntry last = entries.get(entries.size() - 1);

			List<Callable<MockHttpServletResponse>> tasks = List.of(
					() -> check(puzzle, session, last, last.getWord().getEnglish()),
					() -> reveal(puzzle, session));
			List<MockHttpServletResponse> responses = runTogether(tasks);

			int ok = 0;
			int conflict = 0;
			for (MockHttpServletResponse r : responses) {
				if (r.getStatus() == 200) {
					ok++;
				} else if (r.getStatus() == 409) {
					conflict++;
					assertThat(JsonPath.<String>read(r.getContentAsString(), "$.code")).isEqualTo("SESSION_FINISHED");
				}
			}
			assertThat(ok).as("round %d: %s / %s", round, responses.get(0).getContentAsString(),
					responses.get(1).getContentAsString()).isEqualTo(1);
			assertThat(conflict).isEqualTo(1);
		}
	}
}
