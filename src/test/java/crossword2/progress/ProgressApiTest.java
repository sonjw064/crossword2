package crossword2.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import crossword2.auth.AuthTestClient;
import crossword2.auth.AuthTestClient.Tokens;
import crossword2.auth.Owner;
import crossword2.auth.OwnerType;
import crossword2.puzzle.PlayFinished;
import crossword2.puzzle.PlayStatus;
import crossword2.puzzle.Puzzle;
import crossword2.puzzle.PuzzleEntry;
import crossword2.puzzle.PuzzleRepository;
import crossword2.word.Word;
import crossword2.word.WordRepository;

@SpringBootTest
@AutoConfigureMockMvc
class ProgressApiTest {

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

	@Autowired
	ProgressRecorder recorder;

	PlayTestHelper play;
	Puzzle puzzle;
	List<PuzzleEntry> entries;

	@BeforeEach
	void setUp() {
		play = new PlayTestHelper(mvc, puzzleRepository);
		puzzle = play.puzzle();
		entries = puzzle.getEntries();
		assertThat(entries.size()).isGreaterThanOrEqualTo(5);
	}

	private Set<Long> words(List<PuzzleEntry> es) {
		return es.stream().map(e -> e.getWord().getId()).collect(java.util.stream.Collectors.toSet());
	}

	private String fetch(String url, Tokens caller) throws Exception {
		return mvc.perform(get(url).header("Authorization", caller.bearer())).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
	}

	private List<Integer> notebookWordIds(Tokens caller) throws Exception {
		return JsonPath.read(fetch("/api/me/wrong-answers?pageSize=50", caller), "$.items[*].wordId");
	}

	private static String uniqueEmail() {
		return "pg" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
	}

	// ---- 완료 ----

	@Test
	void aCompletedPlayIsRecordedAndTheTroubledWordsGoToTheNotebook() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "기록손님");
		UUID session = play.start(puzzle, guest);
		PuzzleEntry wrongOne = entries.get(0);
		PuzzleEntry letterHinted = entries.get(1);
		PuzzleEntry definitionHinted = entries.get(2);

		play.check(puzzle, session, guest, List.of(wrongOne), false).andExpect(status().isOk());
		play.hint(puzzle, session, guest, letterHinted).andExpect(status().isOk());
		play.definitionHint(puzzle, session, guest, definitionHinted).andExpect(status().isOk());
		play.solve(puzzle, session, guest, entries).andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));

		String progress = fetch("/api/me/progress", guest);
		assertThat((Integer) JsonPath.read(progress, "$.summary.completed")).isEqualTo(1);
		assertThat((Integer) JsonPath.read(progress, "$.summary.gaveUp")).isZero();
		assertThat(JsonPath.<List<Integer>>read(progress, "$.summary.completedPuzzleIds")).containsExactly(puzzle.getId().intValue());
		assertThat((String) JsonPath.read(progress, "$.records[0].status")).isEqualTo("COMPLETED");
		assertThat((Integer) JsonPath.read(progress, "$.records[0].wrongCount")).isEqualTo(1);
		assertThat((Integer) JsonPath.read(progress, "$.records[0].hintCount")).isEqualTo(2);
		assertThat((String) JsonPath.read(progress, "$.records[0].difficulty")).isEqualTo(puzzle.getDifficulty().name());

		assertThat(notebookWordIds(guest)).containsExactlyInAnyOrderElementsOf(
				words(List.of(wrongOne, letterHinted, definitionHinted)).stream().map(Long::intValue).toList());
		String notebook = fetch("/api/me/wrong-answers", guest);
		assertThat((Integer) JsonPath.read(notebook, "$.items[0].count")).isEqualTo(1);
		assertThat((String) JsonPath.read(notebook, "$.items[0].english")).isNotBlank();
		assertThat((String) JsonPath.read(notebook, "$.items[0].korean")).isNotBlank();
	}

	@Test
	void aCleanPlayLeavesTheNotebookEmpty() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "깨끗한손님");
		UUID session = play.start(puzzle, guest);

		play.solve(puzzle, session, guest, entries).andExpect(jsonPath("$.completed").value(true));

		assertThat(notebookWordIds(guest)).isEmpty();
		assertThat((Integer) JsonPath.read(fetch("/api/me/progress", guest), "$.summary.completed")).isEqualTo(1);
	}

	// ---- 정답 보기(포기) ----

	@Test
	void givingUpRecordsTheUnsolvedWordsToo() throws Exception {
		Tokens member = AuthTestClient.signup(mvc, uniqueEmail(), "secret123", "포기회원");
		UUID session = play.start(puzzle, member);
		List<PuzzleEntry> solved = entries.subList(0, 2);
		List<PuzzleEntry> unsolved = entries.subList(2, entries.size());
		play.solve(puzzle, session, member, solved).andExpect(status().isOk());
		play.hint(puzzle, session, member, unsolved.get(0)).andExpect(status().isOk()); // 힌트 + 미해결

		play.reveal(puzzle, session, member).andExpect(status().isOk());

		String progress = fetch("/api/me/progress", member);
		assertThat((Integer) JsonPath.read(progress, "$.summary.gaveUp")).isEqualTo(1);
		assertThat((Integer) JsonPath.read(progress, "$.summary.completed")).isZero();
		assertThat(JsonPath.<List<Integer>>read(progress, "$.summary.completedPuzzleIds")).isEmpty();
		assertThat((String) JsonPath.read(progress, "$.records[0].status")).isEqualTo("GAVE_UP");
		assertThat(notebookWordIds(member)).containsExactlyInAnyOrderElementsOf(
				words(unsolved).stream().map(Long::intValue).toList());
		assertThat(notebookWordIds(member)).doesNotContainAnyElementsOf(words(solved).stream().map(Long::intValue).toList());
	}

	// ---- 익명 ----

	@Test
	void anonymousPlayLeavesNoRecord() throws Exception {
		long before = records.count();
		long notebookBefore = wrongAnswers.count();
		UUID session = play.start(puzzle, null);
		play.check(puzzle, session, null, List.of(entries.get(0)), false).andExpect(status().isOk());
		play.hint(puzzle, session, null, entries.get(1)).andExpect(status().isOk());

		play.reveal(puzzle, session, null).andExpect(status().isOk());

		assertThat(records.count()).isEqualTo(before);
		assertThat(wrongAnswers.count()).isEqualTo(notebookBefore);
	}

	// ---- 정답 보호: 풀이 중에는 노트에 나타나지 않는다 ----

	@Test
	void wordsFromAnUnfinishedPlayNeverShowUpInTheNotebook() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "엿보기손님");
		UUID session = play.start(puzzle, guest);
		play.check(puzzle, session, guest, List.of(entries.get(0)), false).andExpect(status().isOk());
		play.hint(puzzle, session, guest, entries.get(1)).andExpect(status().isOk());
		play.definitionHint(puzzle, session, guest, entries.get(2)).andExpect(status().isOk());

		String notebook = fetch("/api/me/wrong-answers", guest);
		String progress = fetch("/api/me/progress", guest);

		assertThat((List<?>) JsonPath.read(notebook, "$.items")).isEmpty();
		for (PuzzleEntry e : entries) {
			assertThat(notebook).doesNotContain(e.getWord().getEnglish());
			assertThat(progress).doesNotContain("\"" + e.getWord().getEnglish() + "\"");
		}
		assertThat((Integer) JsonPath.read(progress, "$.summary.completed")).isZero();

		// 끝내야(정답 보기) 비로소 나타난다
		play.reveal(puzzle, session, guest).andExpect(status().isOk());
		assertThat(notebookWordIds(guest)).isNotEmpty();
	}

	// ---- 누적 ----

	@Test
	void theSameWordMissedInTwoPlaysCountsTwice() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "반복손님");
		PuzzleEntry weak = entries.get(0);
		for (int i = 0; i < 2; i++) {
			UUID session = play.start(puzzle, guest);
			play.check(puzzle, session, guest, List.of(weak), false).andExpect(status().isOk());
			play.check(puzzle, session, guest, List.of(weak), false).andExpect(status().isOk()); // 같은 판에서 또 틀려도 1회
			play.solve(puzzle, session, guest, entries).andExpect(jsonPath("$.completed").value(true));
		}

		String notebook = fetch("/api/me/wrong-answers", guest);

		assertThat((List<?>) JsonPath.read(notebook, "$.items")).hasSize(1);
		assertThat((Integer) JsonPath.read(notebook, "$.items[0].count")).isEqualTo(2);
		assertThat((Integer) JsonPath.read(fetch("/api/me/progress", guest), "$.summary.completed")).isEqualTo(2);
	}

	// ---- 격리/인증 ----

	@Test
	void everyoneSeesOnlyTheirOwnData() throws Exception {
		Tokens alice = AuthTestClient.signup(mvc, uniqueEmail(), "secret123", "앨리스");
		Tokens bob = AuthTestClient.signup(mvc, uniqueEmail(), "secret123", "밥밥");
		Tokens guest = AuthTestClient.guest(mvc, "지나가는손님");
		UUID session = play.start(puzzle, alice);
		play.check(puzzle, session, alice, List.of(entries.get(0)), false);
		play.solve(puzzle, session, alice, entries);

		assertThat(notebookWordIds(alice)).isNotEmpty();
		assertThat(notebookWordIds(bob)).isEmpty();
		assertThat(notebookWordIds(guest)).isEmpty();
		assertThat((Integer) JsonPath.read(fetch("/api/me/progress", bob), "$.summary.completed")).isZero();
	}

	@Test
	void bothEndpointsRequireLogin() throws Exception {
		mvc.perform(get("/api/me/progress")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/me/wrong-answers")).andExpect(status().isUnauthorized());
	}

	// ---- 목록: 정렬, 페이지, 통계 ----

	@Test
	void notebookSupportsSortingAndPaging() throws Exception {
		Tokens member = AuthTestClient.signup(mvc, uniqueEmail(), "secret123", "정렬회원");
		Owner owner = new Owner(OwnerType.MEMBER, member.ownerId());
		List<Word> some = wordRepository.findAll().stream().limit(5).toList();
		int[] counts = { 3, 1, 2, 5, 4 };
		Instant base = Instant.parse("2026-03-01T00:00:00Z");
		for (int i = 0; i < some.size(); i++) {
			WrongAnswer row = new WrongAnswer(owner, some.get(i).getId(), base.plusSeconds(i * 100L)); // i가 클수록 최근
			for (int c = 1; c < counts[i]; c++) {
				row.recordAgain(base.plusSeconds(i * 100L));
			}
			wrongAnswers.save(row);
		}

		List<Integer> byCount = JsonPath.read(fetch("/api/me/wrong-answers?sort=count", member), "$.items[*].count");
		List<Integer> byRecent = JsonPath.read(fetch("/api/me/wrong-answers?sort=recent", member), "$.items[*].wordId");
		String firstPage = fetch("/api/me/wrong-answers?sort=count&page=0&pageSize=2", member);
		String lastPage = fetch("/api/me/wrong-answers?sort=count&page=2&pageSize=2", member);

		assertThat(byCount).containsExactly(5, 4, 3, 2, 1);
		assertThat(byRecent).containsExactly(
				some.get(4).getId().intValue(), some.get(3).getId().intValue(), some.get(2).getId().intValue(),
				some.get(1).getId().intValue(), some.get(0).getId().intValue());
		assertThat((List<?>) JsonPath.read(firstPage, "$.items")).hasSize(2);
		assertThat((Integer) JsonPath.read(firstPage, "$.totalItems")).isEqualTo(5);
		assertThat((List<?>) JsonPath.read(lastPage, "$.items")).hasSize(1);
		mvc.perform(get("/api/me/wrong-answers?sort=bogus").header("Authorization", member.bearer()))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
		mvc.perform(get("/api/me/wrong-answers?pageSize=1000").header("Authorization", member.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.pageSize").value(ProgressService.MAX_PAGE_SIZE));
	}

	@Test
	void progressSummarizesTimesAndListsNewestFirst() throws Exception {
		Tokens member = AuthTestClient.signup(mvc, uniqueEmail(), "secret123", "통계회원");
		Owner owner = new Owner(OwnerType.MEMBER, member.ownerId());
		Instant base = Instant.parse("2026-03-01T00:00:00Z");
		records.save(new PlayRecord(UUID.randomUUID(), puzzle.getId(), owner, PlayStatus.COMPLETED, 60, 0, 0, base));
		records.save(new PlayRecord(UUID.randomUUID(), puzzle.getId(), owner, PlayStatus.COMPLETED, 120, 1, 2, base.plusSeconds(60)));
		records.save(new PlayRecord(UUID.randomUUID(), puzzle.getId(), owner, PlayStatus.GAVE_UP, 30, 0, 0, base.plusSeconds(120)));

		String progress = fetch("/api/me/progress", member);

		assertThat((Integer) JsonPath.read(progress, "$.summary.completed")).isEqualTo(2);
		assertThat((Integer) JsonPath.read(progress, "$.summary.gaveUp")).isEqualTo(1);
		assertThat((Integer) JsonPath.read(progress, "$.summary.averageSeconds")).isEqualTo(90);
		assertThat((Integer) JsonPath.read(progress, "$.summary.bestSeconds")).isEqualTo(60);
		assertThat(JsonPath.<List<String>>read(progress, "$.records[*].status")).containsExactly("GAVE_UP", "COMPLETED", "COMPLETED");
		assertThat((Integer) JsonPath.read(progress, "$.totalRecords")).isEqualTo(3);
	}

	@Test
	void anEmptyHistoryHasNoAverages() throws Exception {
		Tokens member = AuthTestClient.signup(mvc, uniqueEmail(), "secret123", "빈회원");

		mvc.perform(get("/api/me/progress").header("Authorization", member.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.summary.completed").value(0))
				.andExpect(jsonPath("$.summary.averageSeconds").doesNotExist())
				.andExpect(jsonPath("$.summary.bestSeconds").doesNotExist())
				.andExpect(jsonPath("$.records").isEmpty());
	}

	// ---- 멱등성 ----

	@Test
	void theSameSessionIsRecordedOnlyOnce() {
		Owner owner = new Owner(OwnerType.MEMBER, "999999");
		UUID sessionId = UUID.randomUUID();
		PlayFinished event = new PlayFinished(sessionId, owner, puzzle.getId(), PlayStatus.COMPLETED, 10, 0, 0,
				Instant.parse("2026-03-01T00:00:00Z"), Set.of(entries.get(0).getWord().getId()));

		recorder.on(event);
		recorder.on(event);

		assertThat(records.existsBySessionId(sessionId)).isTrue();
		assertThat(records.findByOwnerTypeAndOwnerId(owner.type(), owner.id(), org.springframework.data.domain.Pageable.unpaged())
				.getTotalElements()).isEqualTo(1);
		assertThat(wrongAnswers.findByOwnerTypeAndOwnerId(owner.type(), owner.id())).singleElement()
				.satisfies(w -> assertThat(w.getCount()).isEqualTo(1));
	}
}
