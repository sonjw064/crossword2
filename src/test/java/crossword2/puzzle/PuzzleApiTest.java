package crossword2.puzzle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import crossword2.word.Word;

@SpringBootTest
@AutoConfigureMockMvc
class PuzzleApiTest {

	private static final String SESSION = "X-Play-Session";

	@Autowired
	MockMvc mvc;

	@Autowired
	PuzzleRepository puzzleRepository;

	private Puzzle somePuzzle() {
		Long id = puzzleRepository.findAll(PageRequest.of(0, 1)).getContent().get(0).getId();
		return puzzleRepository.findWithEntriesById(id).orElseThrow();
	}

	private String startSession(Long puzzleId) throws Exception {
		String body = mvc.perform(post("/api/puzzles/{id}/start", puzzleId)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.sessionId");
	}

	private ResultActions postJson(String url, Object[] vars, String session, String json) throws Exception {
		var req = post(url, vars).contentType(MediaType.APPLICATION_JSON).content(json);
		if (session != null) {
			req = req.header(SESSION, session);
		}
		return mvc.perform(req);
	}

	private ResultActions check(Puzzle p, String session, String answersJson) throws Exception {
		return postJson("/api/puzzles/{id}/check", new Object[] { p.getId() }, session,
				"{\"answers\":" + answersJson + "}");
	}

	private ResultActions entryAction(String path, Puzzle p, String session, Long entryId) throws Exception {
		return postJson("/api/puzzles/{id}/" + path, new Object[] { p.getId() }, session,
				"{\"entryId\":" + entryId + "}");
	}

	private String answer(PuzzleEntry e, String text) {
		return "{\"entryId\":" + e.getId() + ",\"answer\":\"" + text + "\"}";
	}

	private String allCorrect(Puzzle p) {
		return "[" + String.join(",", p.getEntries().stream().map(e -> answer(e, e.getWord().getEnglish())).toList()) + "]";
	}

	private static String wrongAnswerOf(PuzzleEntry e) {
		String w = e.getWord().getEnglish();
		return (w.charAt(0) == 'z' ? "y" : "z") + w.substring(1);
	}

	// ---- 조회 ----

	@Test
	void listsPuzzlesWithFilters() throws Exception {
		mvc.perform(get("/api/puzzles")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(org.hamcrest.Matchers.greaterThan(0)))
				.andExpect(jsonPath("$.totalItems").value(org.hamcrest.Matchers.greaterThanOrEqualTo(20)));

		String body = mvc.perform(get("/api/puzzles").param("difficulty", "EASY").param("size", "7"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<String> difficulties = JsonPath.read(body, "$.items[*].difficulty");
		List<Integer> sizes = JsonPath.read(body, "$.items[*].size");
		assertThat(difficulties).isNotEmpty().containsOnly("EASY");
		assertThat(sizes).containsOnly(7);
	}

	@Test
	void listClampsPageSizeAndRejectsInvalidFilter() throws Exception {
		mvc.perform(get("/api/puzzles").param("pageSize", "1000")).andExpect(status().isOk())
				.andExpect(jsonPath("$.pageSize").value(PuzzleService.MAX_PAGE_SIZE));
		mvc.perform(get("/api/puzzles").param("difficulty", "IMPOSSIBLE")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
	}

	@Test
	void unknownPuzzleIs404() throws Exception {
		mvc.perform(get("/api/puzzles/{id}", 999_999)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PUZZLE_NOT_FOUND"));
	}

	@Test
	void detailHasStructureAndKoreanCluesButNoAnswers() throws Exception {
		Puzzle puzzle = somePuzzle();

		mvc.perform(get("/api/puzzles/{id}", puzzle.getId())).andExpect(status().isOk())
				.andExpect(jsonPath("$.size").value(puzzle.getSize()))
				.andExpect(jsonPath("$.grid.length()").value(puzzle.getSize()))
				.andExpect(jsonPath("$.entries.length()").value(puzzle.getEntries().size()))
				.andExpect(jsonPath("$.entries[0].clue").isNotEmpty())
				.andExpect(jsonPath("$.entries[0].length").isNumber());
	}

	@Test
	void detailNeverLeaksAnswersDefinitionsOrExamplesForAnyPuzzle() throws Exception {
		for (Puzzle summary : puzzleRepository.findAll()) {
			Puzzle puzzle = puzzleRepository.findWithEntriesById(summary.getId()).orElseThrow();
			String body = mvc.perform(get("/api/puzzles/{id}", puzzle.getId())).andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString().toLowerCase(Locale.ROOT);
			for (PuzzleEntry e : puzzle.getEntries()) {
				Word w = e.getWord();
				assertThat(body).as("puzzle %d leaks '%s'", puzzle.getId(), w.getEnglish()).doesNotContain(w.getEnglish());
				assertThat(body).doesNotContain(w.getDefinition().toLowerCase(Locale.ROOT));
				assertThat(body).doesNotContain(w.getExample().toLowerCase(Locale.ROOT));
			}
		}
	}

	// ---- 풀이 흐름 ----

	@Test
	void solvingEveryEntryCompletesThePuzzle() throws Exception {
		Puzzle puzzle = somePuzzle();
		String session = startSession(puzzle.getId());

		check(puzzle, session, allCorrect(puzzle)).andExpect(status().isOk())
				.andExpect(jsonPath("$.completed").value(true))
				.andExpect(jsonPath("$.wrongCount").value(0))
				.andExpect(jsonPath("$.hintCount").value(0))
				.andExpect(jsonPath("$.elapsedSec").value(org.hamcrest.Matchers.greaterThanOrEqualTo(0)))
				.andExpect(jsonPath("$.results[0].status").value("CORRECT"));

		check(puzzle, session, allCorrect(puzzle)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SESSION_FINISHED"));
	}

	@Test
	void wrongAnswerCountsButShortAnswerDoesNot() throws Exception {
		Puzzle puzzle = somePuzzle();
		PuzzleEntry first = puzzle.getEntries().get(0);
		String session = startSession(puzzle.getId());

		check(puzzle, session, "[" + answer(first, wrongAnswerOf(first)) + "]").andExpect(status().isOk())
				.andExpect(jsonPath("$.results[0].status").value("WRONG"))
				.andExpect(jsonPath("$.wrongCount").value(1))
				.andExpect(jsonPath("$.completed").value(false))
				.andExpect(jsonPath("$.elapsedSec").doesNotExist());

		check(puzzle, session, "[" + answer(first, first.getWord().getEnglish().substring(1)) + "]")
				.andExpect(jsonPath("$.results[0].status").value("INCOMPLETE"))
				.andExpect(jsonPath("$.wrongCount").value(1));
	}

	@Test
	void answersAreNormalizedForCaseAndWhitespace() throws Exception {
		Puzzle puzzle = somePuzzle();
		PuzzleEntry first = puzzle.getEntries().get(0);
		String session = startSession(puzzle.getId());

		check(puzzle, session, "[" + answer(first, "  " + first.getWord().getEnglish().toUpperCase(Locale.ROOT) + " ") + "]")
				.andExpect(jsonPath("$.results[0].status").value("CORRECT"));
	}

	@Test
	void hintsRevealFirstLetterAndCountOncePerEntry() throws Exception {
		Puzzle puzzle = somePuzzle();
		PuzzleEntry first = puzzle.getEntries().get(0);
		String session = startSession(puzzle.getId());

		entryAction("hint", puzzle, session, first.getId()).andExpect(status().isOk())
				.andExpect(jsonPath("$.letter").value(first.getWord().getEnglish().substring(0, 1)))
				.andExpect(jsonPath("$.hintCount").value(1));
		entryAction("hint", puzzle, session, first.getId())
				.andExpect(jsonPath("$.hintCount").value(1));
		entryAction("definition-hint", puzzle, session, first.getId()).andExpect(status().isOk())
				.andExpect(jsonPath("$.definition").value(first.getWord().getDefinition()))
				.andExpect(jsonPath("$.hintCount").value(2));
		entryAction("definition-hint", puzzle, session, first.getId())
				.andExpect(jsonPath("$.hintCount").value(2));
	}

	@Test
	void hintOnSolvedEntryIsNotCounted() throws Exception {
		Puzzle puzzle = somePuzzle();
		PuzzleEntry first = puzzle.getEntries().get(0);
		String session = startSession(puzzle.getId());
		check(puzzle, session, "[" + answer(first, first.getWord().getEnglish()) + "]").andExpect(status().isOk());

		entryAction("hint", puzzle, session, first.getId()).andExpect(jsonPath("$.hintCount").value(0));
	}

	@Test
	void revealEndsTheSessionAndUnlocksAllWordCards() throws Exception {
		Puzzle puzzle = somePuzzle();
		String session = startSession(puzzle.getId());

		postJson("/api/puzzles/{id}/reveal", new Object[] { puzzle.getId() }, session, "{}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.entries.length()").value(puzzle.getEntries().size()))
				.andExpect(jsonPath("$.entries[0].card.english").value(puzzle.getEntries().get(0).getWord().getEnglish()));

		check(puzzle, session, allCorrect(puzzle)).andExpect(status().isConflict());
		entryAction("hint", puzzle, session, puzzle.getEntries().get(0).getId()).andExpect(status().isConflict());
		for (PuzzleEntry e : puzzle.getEntries()) {
			mvc.perform(get("/api/words/{id}", e.getWord().getId()).header(SESSION, session))
					.andExpect(status().isOk());
		}
	}

	// ---- 단어 카드 접근 제어 ----

	@Test
	void wordCardIsForbiddenUntilTheWordIsSolved() throws Exception {
		Puzzle puzzle = somePuzzle();
		PuzzleEntry first = puzzle.getEntries().get(0);
		PuzzleEntry second = puzzle.getEntries().get(1);
		String session = startSession(puzzle.getId());

		mvc.perform(get("/api/words/{id}", first.getWord().getId()).header(SESSION, session))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CARD_NOT_AVAILABLE"));

		check(puzzle, session, "[" + answer(first, first.getWord().getEnglish()) + "]").andExpect(status().isOk());

		mvc.perform(get("/api/words/{id}", first.getWord().getId()).header(SESSION, session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.english").value(first.getWord().getEnglish()))
				.andExpect(jsonPath("$.korean").value(first.getWord().getKorean()))
				.andExpect(jsonPath("$.definition").value(first.getWord().getDefinition()));
		mvc.perform(get("/api/words/{id}", second.getWord().getId()).header(SESSION, session))
				.andExpect(status().isForbidden());
	}

	@Test
	void wordCardNeedsASession() throws Exception {
		Puzzle puzzle = somePuzzle();
		Long wordId = puzzle.getEntries().get(0).getWord().getId();

		mvc.perform(get("/api/words/{id}", wordId)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MISSING_SESSION"));
		mvc.perform(get("/api/words/{id}", wordId).header(SESSION, "not-a-uuid")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SESSION"));
		mvc.perform(get("/api/words/{id}", wordId).header(SESSION, java.util.UUID.randomUUID().toString()))
				.andExpect(status().isNotFound());
	}

	// ---- 오류 처리 ----

	@Test
	void sessionIsRequiredAndMustMatchThePuzzle() throws Exception {
		Puzzle puzzle = somePuzzle();
		Puzzle other = puzzleRepository.findAll().stream().filter(p -> !p.getId().equals(puzzle.getId())).findFirst()
				.orElseThrow();
		String otherSession = startSession(other.getId());

		check(puzzle, null, allCorrect(puzzle)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MISSING_SESSION"));
		check(puzzle, otherSession, allCorrect(puzzle)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("SESSION_MISMATCH"));
	}

	@Test
	void startingAnUnknownPuzzleIs404() throws Exception {
		mvc.perform(post("/api/puzzles/{id}/start", 999_999)).andExpect(status().isNotFound());
	}

	@Test
	void rejectsEntriesFromAnotherPuzzleAndInvalidBodies() throws Exception {
		Puzzle puzzle = somePuzzle();
		String session = startSession(puzzle.getId());

		entryAction("hint", puzzle, session, 999_999L).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_ENTRY"));
		check(puzzle, session, "[]").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		postJson("/api/puzzles/{id}/check", new Object[] { puzzle.getId() }, session, "not json")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
		check(puzzle, session, "[{\"entryId\":" + puzzle.getEntries().get(0).getId() + ",\"answer\":\"" + "a".repeat(31) + "\"}]")
				.andExpect(status().isBadRequest());
	}
}
