package crossword2.puzzle;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import crossword2.auth.AuthTestClient;
import crossword2.auth.AuthTestClient.Tokens;

/** 토큰으로 시작한 풀이 세션은 소유자만 쓸 수 있고, 익명 세션은 기존처럼 누구나 쓸 수 있다. */
@SpringBootTest
@AutoConfigureMockMvc
class SessionOwnershipTest {

	private static final String SESSION = "X-Play-Session";

	@Autowired
	MockMvc mvc;

	@Autowired
	PuzzleRepository puzzleRepository;

	private Puzzle somePuzzle() {
		Long id = puzzleRepository.findAll(PageRequest.of(0, 1)).getContent().get(0).getId();
		return puzzleRepository.findWithEntriesById(id).orElseThrow();
	}

	private String start(Puzzle puzzle, Tokens owner) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/puzzles/{id}/start", puzzle.getId());
		if (owner != null) {
			request = request.header("Authorization", owner.bearer());
		}
		String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.sessionId");
	}

	private ResultActions check(Puzzle puzzle, String session, Tokens caller) throws Exception {
		PuzzleEntry first = puzzle.getEntries().get(0);
		MockHttpServletRequestBuilder request = post("/api/puzzles/{id}/check", puzzle.getId())
				.header(SESSION, session).contentType(MediaType.APPLICATION_JSON)
				.content("{\"answers\":[{\"entryId\":" + first.getId() + ",\"answer\":\"" + first.getWord().getEnglish()
						+ "\"}]}");
		if (caller != null) {
			request = request.header("Authorization", caller.bearer());
		}
		return mvc.perform(request);
	}

	private ResultActions reveal(Puzzle puzzle, String session, Tokens caller) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/puzzles/{id}/reveal", puzzle.getId())
				.header(SESSION, session).contentType(MediaType.APPLICATION_JSON).content("{}");
		if (caller != null) {
			request = request.header("Authorization", caller.bearer());
		}
		return mvc.perform(request);
	}

	@Test
	void ownerCanUseTheirSession() throws Exception {
		Puzzle puzzle = somePuzzle();
		Tokens owner = AuthTestClient.guest(mvc, "주인");
		String session = start(puzzle, owner);

		check(puzzle, session, owner).andExpect(status().isOk());
	}

	@Test
	void otherUsersAndAnonymousCallersAreRefused() throws Exception {
		Puzzle puzzle = somePuzzle();
		Tokens owner = AuthTestClient.guest(mvc, "주인");
		Tokens other = AuthTestClient.guest(mvc, "남남");
		String session = start(puzzle, owner);

		check(puzzle, session, other).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("SESSION_FORBIDDEN"));
		check(puzzle, session, null).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("SESSION_FORBIDDEN"));
		mvc.perform(get("/api/words/{id}", puzzle.getEntries().get(0).getWord().getId()).header(SESSION, session)
				.header("Authorization", other.bearer())).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("SESSION_FORBIDDEN"));
	}

	@Test
	void aRefusedRevealDoesNotEndTheOwnersSession() throws Exception {
		Puzzle puzzle = somePuzzle();
		Tokens owner = AuthTestClient.guest(mvc, "주인");
		Tokens other = AuthTestClient.guest(mvc, "방해꾼");
		String session = start(puzzle, owner);

		reveal(puzzle, session, other).andExpect(status().isForbidden());

		check(puzzle, session, owner).andExpect(status().isOk());
	}

	@Test
	void membersOwnTheirSessionsToo() throws Exception {
		Puzzle puzzle = somePuzzle();
		Tokens member = AuthTestClient.signup(mvc, "owner" + System.nanoTime() + "@example.com", "secret123", "회원주인");
		Tokens guest = AuthTestClient.guest(mvc, "손님");
		String session = start(puzzle, member);

		check(puzzle, session, member).andExpect(status().isOk());
		check(puzzle, session, guest).andExpect(status().isForbidden());
	}

	@Test
	void anonymousSessionsStayOpenToEveryone() throws Exception {
		Puzzle puzzle = somePuzzle();
		String session = start(puzzle, null);

		check(puzzle, session, null).andExpect(status().isOk());
		check(puzzle, session, AuthTestClient.guest(mvc, "아무나")).andExpect(status().isOk());
	}
}
