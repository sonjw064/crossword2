package crossword2.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import crossword2.auth.AuthTestClient;
import crossword2.auth.AuthTestClient.Tokens;
import crossword2.auth.Owner;
import crossword2.auth.OwnerType;
import crossword2.puzzle.PlayStatus;
import crossword2.puzzle.Puzzle;
import crossword2.puzzle.PuzzleEntry;
import crossword2.puzzle.PuzzleRepository;
import crossword2.word.Word;
import crossword2.word.WordRepository;

/** 게스트가 회원이 되면 풀이 기록은 옮겨지고 오답노트는 단어별로 병합된다. */
@SpringBootTest
@AutoConfigureMockMvc
class ProgressMigrationTest {

	private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

	@Autowired
	MockMvc mvc;

	@Autowired
	PuzzleRepository puzzleRepository;

	@Autowired
	PlayRecordRepository records;

	@Autowired
	WrongAnswerRepository wrongAnswers;

	@Autowired
	WordRepository wordRepository;

	PlayTestHelper play;
	Puzzle puzzle;
	List<Word> words;

	@BeforeEach
	void setUp() {
		play = new PlayTestHelper(mvc, puzzleRepository);
		puzzle = play.puzzle();
		words = wordRepository.findAll().stream().limit(4).toList();
	}

	private static String uniqueEmail() {
		return "mg" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
	}

	private WrongAnswer row(Owner owner, Word word, int count, Instant lastAt) {
		WrongAnswer w = new WrongAnswer(owner, word.getId(), lastAt);
		for (int i = 1; i < count; i++) {
			w.recordAgain(lastAt);
		}
		return wrongAnswers.save(w);
	}

	private void login(String email, Tokens guest) throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", guest.bearer())
				.content("{\"email\":\"" + email + "\",\"password\":\"secret123\",\"guestId\":\"" + guest.ownerId() + "\"}"))
				.andExpect(status().isOk());
	}

	@Test
	void recordsMoveAndNotebookEntriesMergeByWord() throws Exception {
		String email = uniqueEmail();
		Tokens member = AuthTestClient.signup(mvc, email, "secret123", "병합회원");
		Tokens guest = AuthTestClient.guest(mvc, "병합손님");
		Owner m = new Owner(OwnerType.MEMBER, member.ownerId());
		Owner g = new Owner(OwnerType.GUEST, guest.ownerId());
		// 회원: 단어0을 3번(예전), 게스트: 단어0을 2번(더 최근)과 단어1을 1번
		row(m, words.get(0), 3, T0);
		row(g, words.get(0), 2, T0.plusSeconds(1000));
		row(g, words.get(1), 1, T0.plusSeconds(500));
		records.save(new PlayRecord(UUID.randomUUID(), puzzle.getId(), g, PlayStatus.COMPLETED, 50, 0, 0, T0));
		records.save(new PlayRecord(UUID.randomUUID(), puzzle.getId(), g, PlayStatus.GAVE_UP, 20, 1, 1, T0.plusSeconds(1)));
		records.save(new PlayRecord(UUID.randomUUID(), puzzle.getId(), m, PlayStatus.COMPLETED, 70, 0, 0, T0.plusSeconds(2)));
		// 관련 없는 다른 게스트와 회원의 데이터
		Tokens bystander = AuthTestClient.guest(mvc, "지나가는손님");
		Owner b = new Owner(OwnerType.GUEST, bystander.ownerId());
		row(b, words.get(0), 7, T0);
		records.save(new PlayRecord(UUID.randomUUID(), puzzle.getId(), b, PlayStatus.COMPLETED, 99, 0, 0, T0));

		login(email, guest);

		List<WrongAnswer> mine = wrongAnswers.findByOwnerTypeAndOwnerId(m.type(), m.id());
		assertThat(mine).hasSize(2);
		WrongAnswer merged = mine.stream().filter(w -> w.getWordId().equals(words.get(0).getId())).findFirst().orElseThrow();
		assertThat(merged.getCount()).as("횟수 합산").isEqualTo(5);
		assertThat(merged.getLastAt()).as("더 최근 시각").isEqualTo(T0.plusSeconds(1000));
		WrongAnswer moved = mine.stream().filter(w -> w.getWordId().equals(words.get(1).getId())).findFirst().orElseThrow();
		assertThat(moved.getCount()).isEqualTo(1);
		assertThat(wrongAnswers.findByOwnerTypeAndOwnerId(g.type(), g.id())).as("게스트 행은 사라진다").isEmpty();

		assertThat(records.findByOwnerTypeAndOwnerId(m.type(), m.id(), org.springframework.data.domain.Pageable.unpaged()).getTotalElements())
				.isEqualTo(3);
		assertThat(records.findByOwnerTypeAndOwnerId(g.type(), g.id(), org.springframework.data.domain.Pageable.unpaged()).getTotalElements())
				.isZero();

		// 다른 사람의 데이터는 그대로
		assertThat(wrongAnswers.findByOwnerTypeAndOwnerId(b.type(), b.id())).singleElement()
				.satisfies(w -> assertThat(w.getCount()).isEqualTo(7));
		assertThat(records.findByOwnerTypeAndOwnerId(b.type(), b.id(), org.springframework.data.domain.Pageable.unpaged()).getTotalElements())
				.isEqualTo(1);
	}

	@Test
	void theOlderGuestTimestampDoesNotOverwriteTheNewerMemberOne() throws Exception {
		String email = uniqueEmail();
		Tokens member = AuthTestClient.signup(mvc, email, "secret123", "최근회원");
		Tokens guest = AuthTestClient.guest(mvc, "오래된손님");
		Owner m = new Owner(OwnerType.MEMBER, member.ownerId());
		row(m, words.get(2), 1, T0.plusSeconds(5000));
		row(new Owner(OwnerType.GUEST, guest.ownerId()), words.get(2), 1, T0);

		login(email, guest);

		assertThat(wrongAnswers.findByOwnerTypeAndOwnerId(m.type(), m.id())).singleElement().satisfies(w -> {
			assertThat(w.getCount()).isEqualTo(2);
			assertThat(w.getLastAt()).isEqualTo(T0.plusSeconds(5000));
		});
	}

	@Test
	void aRealPlayAsAGuestShowsUpInTheMembersProgressAfterSignup() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "실제손님");
		UUID session = play.start(puzzle, guest);
		PuzzleEntry weak = puzzle.getEntries().get(0);
		play.check(puzzle, session, guest, List.of(weak), false).andExpect(status().isOk());
		play.solve(puzzle, session, guest, puzzle.getEntries()).andExpect(status().isOk());

		String body = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", guest.bearer())
				.content("{\"email\":\"" + uniqueEmail() + "\",\"password\":\"secret123\",\"nickname\":\"이어받음\",\"guestId\":\""
						+ guest.ownerId() + "\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String memberToken = JsonPath.read(body, "$.accessToken");

		String progress = mvc.perform(get("/api/me/progress").header("Authorization", "Bearer " + memberToken))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String notebook = mvc.perform(get("/api/me/wrong-answers").header("Authorization", "Bearer " + memberToken))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		assertThat((Integer) JsonPath.read(progress, "$.summary.completed")).isEqualTo(1);
		assertThat(JsonPath.<List<Integer>>read(progress, "$.summary.completedPuzzleIds")).containsExactly(puzzle.getId().intValue());
		assertThat(JsonPath.<List<Integer>>read(notebook, "$.items[*].wordId")).containsExactly(weak.getWord().getId().intValue());
	}
}
