package crossword2.progress;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import crossword2.auth.AuthTestClient.Tokens;
import crossword2.puzzle.Puzzle;
import crossword2.puzzle.PuzzleEntry;
import crossword2.puzzle.PuzzleRepository;

/** 퍼즐을 API로 실제로 풀어 보는 테스트 도우미. */
final class PlayTestHelper {

	private static final String SESSION = "X-Play-Session";

	private final MockMvc mvc;
	private final PuzzleRepository puzzles;

	PlayTestHelper(MockMvc mvc, PuzzleRepository puzzles) {
		this.mvc = mvc;
		this.puzzles = puzzles;
	}

	/** 항목이 5개 이상인 첫 퍼즐 (항목과 단어까지 로딩됨). */
	Puzzle puzzle() {
		Long id = puzzles.findAll().stream().filter(p -> p.getWordCount() >= 5).map(Puzzle::getId).sorted().findFirst()
				.orElseThrow();
		return puzzles.findWithEntriesById(id).orElseThrow();
	}

	UUID start(Puzzle puzzle, Tokens owner) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/puzzles/{id}/start", puzzle.getId());
		if (owner != null) {
			request = request.header("Authorization", owner.bearer());
		}
		String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.sessionId"));
	}

	private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder request, Tokens caller, UUID session) {
		request = request.header(SESSION, session.toString()).contentType(MediaType.APPLICATION_JSON);
		return caller == null ? request : request.header("Authorization", caller.bearer());
	}

	ResultActions check(Puzzle puzzle, UUID session, Tokens caller, List<PuzzleEntry> entries, boolean correct)
			throws Exception {
		List<String> answers = new ArrayList<>();
		for (PuzzleEntry e : entries) {
			answers.add("{\"entryId\":" + e.getId() + ",\"answer\":\"" + (correct ? e.getWord().getEnglish() : wrongOf(e)) + "\"}");
		}
		return mvc.perform(withAuth(post("/api/puzzles/{id}/check", puzzle.getId()), caller, session)
				.content("{\"answers\":[" + String.join(",", answers) + "]}"));
	}

	ResultActions hint(Puzzle puzzle, UUID session, Tokens caller, PuzzleEntry entry) throws Exception {
		return mvc.perform(withAuth(post("/api/puzzles/{id}/hint", puzzle.getId()), caller, session)
				.content("{\"entryId\":" + entry.getId() + "}"));
	}

	ResultActions definitionHint(Puzzle puzzle, UUID session, Tokens caller, PuzzleEntry entry) throws Exception {
		return mvc.perform(withAuth(post("/api/puzzles/{id}/definition-hint", puzzle.getId()), caller, session)
				.content("{\"entryId\":" + entry.getId() + "}"));
	}

	ResultActions reveal(Puzzle puzzle, UUID session, Tokens caller) throws Exception {
		return mvc.perform(withAuth(post("/api/puzzles/{id}/reveal", puzzle.getId()), caller, session).content("{}"));
	}

	/** 주어진 항목을 모두 맞게 제출한다. */
	ResultActions solve(Puzzle puzzle, UUID session, Tokens caller, List<PuzzleEntry> entries) throws Exception {
		return check(puzzle, session, caller, entries, true);
	}

	List<PuzzleEntry> except(Puzzle puzzle, Set<Long> entryIds) {
		return puzzle.getEntries().stream().filter(e -> !entryIds.contains(e.getId())).toList();
	}

	static String wrongOf(PuzzleEntry e) {
		String w = e.getWord().getEnglish();
		return (w.charAt(0) == 'z' ? "y" : "z") + w.substring(1);
	}
}
