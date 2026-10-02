package crossword2.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import crossword2.auth.AuthTestClient;
import crossword2.auth.AuthTestClient.Tokens;
import crossword2.auth.MutableClock;
import crossword2.auth.OwnerType;
import crossword2.puzzle.PuzzleEntry;
import crossword2.puzzle.PuzzleRepository;

/** 레이스 경기: 점수, 방송, 종료, 재접속, 저장, 대련 중 학습 기능 차단. 시간은 MutableClock으로 움직인다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(RoomMatchStompTest.ClockConfig.class)
class RoomMatchStompTest {

	@TestConfiguration
	static class ClockConfig {

		@Bean
		@Primary
		MutableClock matchClock() {
			return new MutableClock(Instant.now());
		}
	}

	@Value("${local.server.port}")
	int port;

	@Autowired
	MockMvc mvc;

	@Autowired
	Clock clock;

	@Autowired
	PuzzleRepository puzzles;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	MatchParticipantRepository participants;

	@Autowired
	MatchResultRepository results;

	@Autowired
	RoomRegistry registry;

	private final List<StompTestClient> opened = new ArrayList<>();

	@AfterEach
	void closeSockets() {
		opened.forEach(StompTestClient::close);
	}

	private MutableClock clock() {
		return (MutableClock) clock;
	}

	// ---------- 도우미 ----------

	private Tokens guest(String nickname) throws Exception {
		return AuthTestClient.guest(mvc, nickname);
	}

	private static String topic(String code) {
		return "/topic/rooms/" + code;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> room(Map<String, Object> event) {
		return (Map<String, Object>) event.get("room");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> match(Map<String, Object> event) {
		return (Map<String, Object>) room(event).get("match");
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> progress(Map<String, Object> event) {
		return (List<Map<String, Object>>) match(event).get("progress");
	}

	private Map<String, Object> event(StompTestClient socket, String code, String type) throws Exception {
		return socket.nextMatching(topic(code), m -> type.equals(m.get("type")));
	}

	private String createRoom(Tokens host, int timeLimitSec) throws Exception {
		String body = mvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON).header("Authorization", host.bearer())
				.content("{\"difficulty\":\"EASY\",\"size\":7,\"timeLimitSec\":" + timeLimitSec + ",\"maxPlayers\":3,\"mode\":\"RACE\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.code");
	}

	private StompTestClient open(Tokens who, String code) throws Exception {
		StompTestClient socket = StompTestClient.connect(port, who.accessToken());
		opened.add(socket);
		socket.subscribe("/user/queue/me");
		socket.subscribe("/user/queue/errors");
		socket.subscribe("/user/queue/submit");
		socket.subscribe(topic(code));
		return socket;
	}

	private StompTestClient join(Tokens who, String code) throws Exception {
		StompTestClient socket = open(who, code);
		socket.send("/app/rooms/" + code + "/join", null);
		assertThat(socket.next("/user/queue/me")).isNotNull();
		return socket;
	}

	/** entryId → 영어 정답. 서버가 어떤 응답으로도 주지 않는 값이라 테스트가 DB에서 직접 읽는다. */
	private Map<Long, String> answers(long puzzleId) {
		return tx.execute(s -> puzzles.findWithEntriesById(puzzleId).orElseThrow().getEntries().stream()
				.collect(Collectors.toMap(PuzzleEntry::getId, e -> e.getWord().getEnglish())));
	}

	private final class Duel {
		String code;
		Tokens hostT;
		Tokens bobT;
		StompTestClient host;
		StompTestClient bob;
		long puzzleId;
		Map<Long, String> answers;
		List<Long> entryIds;

		void submit(StompTestClient who, long entryId, String answer) {
			who.send("/app/rooms/" + code + "/submit", Map.of("entryId", entryId, "answer", answer));
		}

		void submitCorrect(StompTestClient who, long entryId) {
			submit(who, entryId, answers.get(entryId));
		}
	}

	/** 방을 만들고 둘이 입장해 준비한 뒤 시작한다(카운트다운은 아직 끝나지 않았다). */
	private Duel startDuel(int timeLimitSec) throws Exception {
		Duel d = new Duel();
		d.hostT = guest("대결방장");
		d.bobT = guest("대결밥아");
		d.code = createRoom(d.hostT, timeLimitSec);
		d.host = join(d.hostT, d.code);
		d.bob = join(d.bobT, d.code);
		d.bob.send("/app/rooms/" + d.code + "/ready", Map.of("ready", true));
		event(d.host, d.code, "READY");
		d.host.send("/app/rooms/" + d.code + "/start", null);
		Map<String, Object> started = event(d.bob, d.code, "START");
		event(d.host, d.code, "START");
		d.puzzleId = ((Number) room(started).get("puzzleId")).longValue();
		d.answers = answers(d.puzzleId);
		d.entryIds = new ArrayList<>(d.answers.keySet());
		return d;
	}

	private Duel startedDuel() throws Exception {
		Duel d = startDuel(60);
		clock().advance(Duration.ofSeconds(3)); // 카운트다운이 끝난다
		return d;
	}

	/** 방송/응답 어디에도 정답 문자열이 값으로 들어 있지 않다. */
	private static void assertNoAnswer(Object tree, Collection<String> answers) {
		if (tree instanceof Map<?, ?> map) {
			map.values().forEach(v -> assertNoAnswer(v, answers));
		} else if (tree instanceof Collection<?> list) {
			list.forEach(v -> assertNoAnswer(v, answers));
		} else if (tree instanceof String text) {
			assertThat(answers).as("정답이 응답에 실렸다: " + text).noneMatch(a -> a.equalsIgnoreCase(text));
		}
	}

	private static void drain(StompTestClient socket, String destination) throws Exception {
		while (socket.next(destination, 150) != null) {
			// 쌓인 메시지를 비운다
		}
	}

	// ---------- 시작과 카운트다운 ----------

	@Test
	void startingPublishesTheMatchWithoutAnswersAndSubmissionsWaitForTheCountdown() throws Exception {
		Duel d = startDuel(60);

		d.submitCorrect(d.host, d.entryIds.get(0));
		Map<String, Object> error = d.host.next("/user/queue/errors");
		assertThat(error.get("code")).isEqualTo("MATCH_NOT_STARTED");

		clock().advance(Duration.ofSeconds(3));
		d.submitCorrect(d.host, d.entryIds.get(0));
		Map<String, Object> ack = d.host.next("/user/queue/submit");
		assertThat(ack.get("status")).isEqualTo("CORRECT");
	}

	@Test
	void theStartEventCarriesTheScheduleAndEmptyProgressButNoAnswers() throws Exception {
		Duel d = startDuel(60);
		d.host.send("/app/rooms/" + d.code + "/sync", null);
		Map<String, Object> snapshot = d.host.next("/user/queue/me");

		Map<String, Object> m = match(snapshot);
		assertThat(((Number) m.get("endsAtMs")).longValue() - ((Number) m.get("startsAtMs")).longValue()).isEqualTo(60_000L);
		assertThat(((Number) m.get("startsAtMs")).longValue() - ((Number) m.get("serverNowMs")).longValue()).isEqualTo(3_000L);
		assertThat(m.get("totalEntries")).isEqualTo(d.entryIds.size());
		assertThat(progress(snapshot)).allSatisfy(p -> {
			assertThat(p.get("solved")).isEqualTo(0);
			assertThat(p.get("rank")).isNull();
		});
		assertNoAnswer(snapshot, d.answers.values());
	}

	// ---------- 제출과 점수 ----------

	@Test
	void aCorrectWordScoresAndEveryoneSeesOnlyTheProgress() throws Exception {
		Duel d = startedDuel();
		long entry = d.entryIds.get(0);
		int expected = d.answers.get(entry).length() * 10;

		d.submitCorrect(d.bob, entry);
		Map<String, Object> ack = d.bob.next("/user/queue/submit");
		Map<String, Object> update = event(d.host, d.code, "SCORE_UPDATE");

		assertThat(ack.get("status")).isEqualTo("CORRECT");
		assertThat(ack.get("gained")).isEqualTo(expected);
		assertThat(ack.get("score")).isEqualTo(expected);
		assertThat(progress(update)).filteredOn(p -> ((Number) p.get("playerId")).intValue() == 2).singleElement()
				.satisfies(p -> {
					assertThat(p.get("solved")).isEqualTo(1);
					assertThat(p.get("score")).isEqualTo(expected);
				});
		assertThat(update.toString()).as("누가 어떤 단어를 맞혔는지는 알리지 않는다").doesNotContain("entryId");
		assertNoAnswer(update, d.answers.values());
		assertNoAnswer(ack, d.answers.values());
	}

	@Test
	void aWrongAnswerIsAnsweredPrivatelyAndNotBroadcast() throws Exception {
		Duel d = startedDuel();
		long entry = d.entryIds.get(0);
		String wrong = "x".repeat(d.answers.get(entry).length());
		drain(d.host, topic(d.code));

		d.submit(d.bob, entry, wrong);

		assertThat(d.bob.next("/user/queue/submit").get("status")).isEqualTo("WRONG");
		assertThat(d.host.next(topic(d.code), 400)).as("틀린 제출은 방송하지 않는다").isNull();
	}

	@Test
	void anIncompleteWordAndADuplicateAreHandledWithoutPoints() throws Exception {
		Duel d = startedDuel();
		long entry = d.entryIds.get(0);

		d.submit(d.bob, entry, "a");
		assertThat(d.bob.next("/user/queue/submit").get("status")).isEqualTo("INCOMPLETE");
		d.submitCorrect(d.bob, entry);
		d.bob.next("/user/queue/submit");
		clock().advance(Duration.ofSeconds(1)); // 제출 속도 제한(초당 3회)을 피한다
		d.submitCorrect(d.bob, entry);

		Map<String, Object> again = d.bob.next("/user/queue/submit");
		assertThat(again.get("status")).isEqualTo("ALREADY_SOLVED");
		assertThat(again.get("gained")).isEqualTo(0);
	}

	@Test
	void submissionsFromOutsidersAndMalformedOnesAreRejected() throws Exception {
		Duel d = startedDuel();
		StompTestClient outsider = open(guest("지나가는사람"), d.code);
		// 경기 중인 방의 토픽은 참가자만 구독할 수 있으므로 연결이 닫힌다 -> 개인 큐만 쓰는 새 연결로 시도한다
		StompTestClient quiet = StompTestClient.connect(port, guest("조용한사람").accessToken());
		opened.add(quiet);
		quiet.subscribe("/user/queue/errors");
		quiet.send("/app/rooms/" + d.code + "/submit", Map.of("entryId", d.entryIds.get(0), "answer", "abc"));
		assertThat(quiet.next("/user/queue/errors").get("code")).isEqualTo("NOT_IN_ROOM");

		d.bob.send("/app/rooms/" + d.code + "/submit", Map.of("answer", "abc"));
		assertThat(d.bob.next("/user/queue/errors").get("code")).isIn("MALFORMED_REQUEST", "VALIDATION_FAILED");
		d.bob.send("/app/rooms/" + d.code + "/submit", Map.of("entryId", 999999999L, "answer", "abc"));
		assertThat(d.bob.next("/user/queue/errors").get("code")).isEqualTo("INVALID_ENTRY");
		assertThat(outsider.awaitClosed()).isTrue();
	}

	@Test
	void submittingTooFastIsRefused() throws Exception {
		Duel d = startedDuel();
		long entry = d.entryIds.get(0);

		for (int i = 0; i < 4; i++) {
			d.submit(d.bob, entry, "x");
		}

		List<Object> codes = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			Map<String, Object> reply = d.bob.next("/user/queue/submit", 1000);
			codes.add(reply != null ? reply.get("status") : d.bob.next("/user/queue/errors", 1000).get("code"));
		}
		assertThat(codes).contains("RATE_LIMITED");
	}

	@Test
	@SuppressWarnings("unchecked")
	void aPlayersOwnSolvedWordsComeBackOnSyncButNeverToTheOthers() throws Exception {
		Duel d = startedDuel();
		long entry = d.entryIds.get(0);
		d.submitCorrect(d.bob, entry);
		d.bob.next("/user/queue/submit");

		d.bob.send("/app/rooms/" + d.code + "/sync", null);
		Map<String, Object> mine = d.bob.next("/user/queue/me");
		d.host.send("/app/rooms/" + d.code + "/sync", null);
		Map<String, Object> theirs = d.host.next("/user/queue/me");

		List<Map<String, Object>> solved = (List<Map<String, Object>>) mine.get("solved");
		assertThat(solved).singleElement().satisfies(w -> {
			assertThat(((Number) w.get("entryId")).longValue()).isEqualTo(entry);
			assertThat(w.get("word")).isEqualTo(d.answers.get(entry));
		});
		assertThat((List<?>) theirs.get("solved")).isEmpty();
		assertThat(theirs.toString()).doesNotContain(d.answers.get(entry));
	}

	@Test
	void theLobbyAckCarriesNoSolvedWords() throws Exception {
		Tokens hostTokens = guest("복원방장");
		String code = createRoom(hostTokens, 60);
		StompTestClient host = open(hostTokens, code);
		host.send("/app/rooms/" + code + "/join", null);

		assertThat((List<?>) host.next("/user/queue/me").get("solved")).isEmpty();
	}

	// ---------- 종료와 결과 ----------

	@Test
	void finishingEveryWordEndsTheMatchAndRanksByScoreWithFinishBonus() throws Exception {
		Duel d = startedDuel();
		for (long id : d.entryIds) {
			d.submitCorrect(d.host, id);
			assertThat(d.host.next("/user/queue/submit").get("status")).isEqualTo("CORRECT");
			clock().advance(Duration.ofMillis(400)); // 시계가 멈춰 있으므로 제출 속도 제한(초당 3회)을 피하려고 움직인다
		}
		int letters = d.answers.values().stream().mapToInt(String::length).sum();
		long firstEntry = d.entryIds.get(0);
		d.submitCorrect(d.bob, firstEntry); // 밥은 한 단어만

		d.bob.send("/app/rooms/" + d.code + "/leave", null); // 밥이 나가 푸는 사람이 없어지면 끝난다
		Map<String, Object> end = event(d.host, d.code, "END");

		assertThat(room(end).get("status")).isEqualTo("ENDED");
		assertThat(progress(end)).filteredOn(p -> ((Number) p.get("playerId")).intValue() == 1).singleElement().satisfies(p -> {
			assertThat(p.get("score")).as("글자 수 x 10 + 1등 보너스 100").isEqualTo(letters * 10 + 100);
			assertThat(p.get("finished")).isEqualTo(true);
			assertThat(p.get("rank")).isEqualTo(1);
		});
		assertThat(progress(end)).filteredOn(p -> ((Number) p.get("playerId")).intValue() == 2).singleElement().satisfies(p -> {
			assertThat(p.get("rank")).isEqualTo(2);
			assertThat(p.get("abandoned")).isEqualTo(true);
		});
		assertNoAnswer(end, d.answers.values());
	}

	@Test
	void whenTimeRunsOutTheMatchEndsAndIsSavedForEveryParticipant() throws Exception {
		Duel d = startedDuel();
		long entry = d.entryIds.get(0);
		d.submitCorrect(d.host, entry);
		event(d.bob, d.code, "SCORE_UPDATE");

		clock().advance(Duration.ofSeconds(60));
		Map<String, Object> end = event(d.bob, d.code, "END");

		assertThat(room(end).get("status")).isEqualTo("ENDED");
		var hostRows = participants.findByOwnerTypeAndOwnerId(OwnerType.GUEST, d.hostT.ownerId());
		var bobRows = participants.findByOwnerTypeAndOwnerId(OwnerType.GUEST, d.bobT.ownerId());
		assertThat(hostRows).singleElement().satisfies(r -> {
			assertThat(r.getRank()).isEqualTo(1);
			assertThat(r.getScore()).isEqualTo(d.answers.get(entry).length() * 10);
			assertThat(r.getSolvedCount()).isEqualTo(1);
			assertThat(r.getNickname()).isEqualTo("대결방장");
		});
		assertThat(bobRows).singleElement().satisfies(r -> {
			assertThat(r.getRank()).isEqualTo(2);
			assertThat(r.getScore()).isZero();
		});
		assertThat(results.findById(hostRows.get(0).getMatchId())).get().satisfies(m -> {
			assertThat(m.getPuzzleId()).isEqualTo(d.puzzleId);
			assertThat(m.getPlayerCount()).isEqualTo(2);
		});
	}

	@Test
	void nothingCanBeSubmittedAfterTheMatchEnded() throws Exception {
		Duel d = startedDuel();
		clock().advance(Duration.ofSeconds(60));
		event(d.host, d.code, "END");

		d.submitCorrect(d.host, d.entryIds.get(0));

		assertThat(d.host.next("/user/queue/errors").get("code")).isEqualTo("INVALID_STATE");
	}

	@Test
	void theResultWithWordCardsIsOnlyForParticipantsAndOnlyAfterTheMatch() throws Exception {
		Duel d = startedDuel();
		Tokens stranger = guest("낯선이");

		mvc.perform(get("/api/rooms/{code}/result", d.code).header("Authorization", d.hostT.bearer()))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MATCH_NOT_ENDED"));

		clock().advance(Duration.ofSeconds(60));
		event(d.host, d.code, "END");

		MvcResult ok = mvc.perform(get("/api/rooms/{code}/result", d.code).header("Authorization", d.bobT.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.puzzleId").value(d.puzzleId))
				.andExpect(jsonPath("$.standings.length()").value(2)).andExpect(jsonPath("$.standings[?(@.you == true)].nickname").value("대결밥아"))
				.andExpect(jsonPath("$.cards.length()").value(d.entryIds.size())).andReturn();
		String json = ok.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		assertThat(json).contains(d.answers.values().iterator().next());
		assertThat(json).doesNotContain(d.hostT.ownerId()).doesNotContain(d.bobT.ownerId());

		mvc.perform(get("/api/rooms/{code}/result", d.code).header("Authorization", stranger.bearer())).andExpect(status().isForbidden());
		mvc.perform(get("/api/rooms/{code}/result", d.code)).andExpect(status().isUnauthorized());
	}

	// ---------- 다시 하기 ----------

	@Test
	void onlyTheHostCanStartARematchAndItGoesBackToTheLobbyWithADifferentPuzzle() throws Exception {
		Duel d = startedDuel();
		clock().advance(Duration.ofSeconds(60));
		event(d.host, d.code, "END");

		d.bob.send("/app/rooms/" + d.code + "/rematch", null);
		assertThat(d.bob.next("/user/queue/errors").get("code")).isEqualTo("NOT_HOST");
		d.host.send("/app/rooms/" + d.code + "/rematch", null);
		Map<String, Object> back = event(d.bob, d.code, "REMATCH");

		assertThat(room(back).get("status")).isEqualTo("WAITING");
		assertThat(room(back).get("puzzleId")).isNull();
		assertThat(room(back).get("match")).isNull();
		mvc.perform(get("/api/rooms/{code}/result", d.code).header("Authorization", d.hostT.bearer())).andExpect(status().isConflict());

		d.bob.send("/app/rooms/" + d.code + "/ready", Map.of("ready", true));
		event(d.host, d.code, "READY");
		d.host.send("/app/rooms/" + d.code + "/start", null);
		Map<String, Object> second = event(d.host, d.code, "START");
		assertThat(((Number) room(second).get("puzzleId")).longValue()).as("같은 방에서 같은 퍼즐이 연달아 나오지 않는다").isNotEqualTo(d.puzzleId);
	}

	@Test
	void aRematchIsNotPossibleWhileThePreviousMatchIsRunning() throws Exception {
		Duel d = startedDuel();

		d.host.send("/app/rooms/" + d.code + "/rematch", null);

		assertThat(d.host.next("/user/queue/errors").get("code")).isEqualTo("INVALID_STATE");
	}

	// ---------- 재접속과 이탈 ----------

	@Test
	void aPlayerWhoReconnectsDuringTheMatchKeepsTheirScoreAndTheirSeat() throws Exception {
		Duel d = startedDuel();
		long entry = d.entryIds.get(0);
		d.submitCorrect(d.bob, entry);
		d.bob.next("/user/queue/submit");
		d.bob.disconnect();
		event(d.host, d.code, "DISCONNECT");

		StompTestClient again = open(d.bobT, d.code);
		again.send("/app/rooms/" + d.code + "/join", null);
		Map<String, Object> ack = again.next("/user/queue/me");

		assertThat(ack.get("rejoined")).isEqualTo(true);
		assertThat(ack.get("playerId")).isEqualTo(2);
		assertThat(progress(ack)).filteredOn(p -> ((Number) p.get("playerId")).intValue() == 2).singleElement()
				.satisfies(p -> assertThat(p.get("solved")).isEqualTo(1));
		assertThat(room(ack).get("status")).isEqualTo("PLAYING");
		d.submitCorrect(again, d.entryIds.get(1));
		assertThat(again.next("/user/queue/submit").get("status")).isEqualTo("CORRECT");
	}

	@Test
	void aPlayerWhoStaysAwayLongerThanTheGraceIsMarkedAsAbandonedAndTheMatchGoesOn() throws Exception {
		Duel d = startDuel(600);
		clock().advance(Duration.ofSeconds(3));
		d.bob.disconnect();
		event(d.host, d.code, "DISCONNECT");

		clock().advance(Duration.ofSeconds(61));
		Map<String, Object> left = event(d.host, d.code, "LEAVE");

		assertThat(room(left).get("status")).as("방장은 아직 푸는 중이라 경기는 계속된다").isEqualTo("PLAYING");
		assertThat(progress(left)).filteredOn(p -> ((Number) p.get("playerId")).intValue() == 2).singleElement()
				.satisfies(p -> assertThat(p.get("abandoned")).isEqualTo(true));
	}

	@Test
	void whenEveryoneStaysAwayTheMatchEndsAndTheRoomIsEventuallyRemovedButTheResultIsKept() throws Exception {
		Duel d = startedDuel();
		d.submitCorrect(d.host, d.entryIds.get(0));
		d.host.next("/user/queue/submit");
		d.host.disconnect();
		d.bob.disconnect();
		Thread.sleep(300);

		clock().advance(Duration.ofSeconds(61));
		long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline && participants.findByOwnerTypeAndOwnerId(OwnerType.GUEST, d.hostT.ownerId()).isEmpty()) {
			Thread.sleep(100);
		}

		var hostRows = participants.findByOwnerTypeAndOwnerId(OwnerType.GUEST, d.hostT.ownerId());
		assertThat(hostRows).singleElement().satisfies(r -> {
			assertThat(r.getRank()).isEqualTo(1);
			assertThat(r.isAbandoned()).isTrue();
		});
	}

	@Test
	void leavingTheRoomDuringTheMatchKeepsTheScoreInTheResult() throws Exception {
		Duel d = startedDuel();
		d.submitCorrect(d.bob, d.entryIds.get(0));
		d.bob.next("/user/queue/submit");
		d.bob.send("/app/rooms/" + d.code + "/leave", null);
		event(d.host, d.code, "LEAVE");
		d.host.send("/app/rooms/" + d.code + "/leave", null);
		long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline && participants.findByOwnerTypeAndOwnerId(OwnerType.GUEST, d.bobT.ownerId()).isEmpty()) {
			Thread.sleep(100);
		}

		assertThat(participants.findByOwnerTypeAndOwnerId(OwnerType.GUEST, d.bobT.ownerId())).singleElement().satisfies(r -> {
			assertThat(r.getRank()).isEqualTo(1);
			assertThat(r.getScore()).isPositive();
		});
		assertThat(registry.find(d.code)).as("모두 나간 방은 사라진다").isEmpty();
	}

	// ---------- 대련 중 학습 기능 차단 ----------

	@Test
	void participantsCannotUseLearningAidsDuringTheMatchButOthersCan() throws Exception {
		Duel d = startedDuel();
		Tokens other = guest("딴사람");
		long puzzle = d.puzzleId;
		String fakeSession = UUID.randomUUID().toString();

		for (Tokens participant : List.of(d.hostT, d.bobT)) {
			mvc.perform(post("/api/puzzles/{id}/start", puzzle).header("Authorization", participant.bearer()))
					.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BATTLE_IN_PROGRESS"));
			for (String path : List.of("hint", "definition-hint")) {
				mvc.perform(post("/api/puzzles/{id}/" + path, puzzle).header("Authorization", participant.bearer())
						.header("X-Play-Session", fakeSession).contentType(MediaType.APPLICATION_JSON)
						.content("{\"entryId\":" + d.entryIds.get(0) + "}"))
						.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BATTLE_IN_PROGRESS"));
			}
			mvc.perform(post("/api/puzzles/{id}/check", puzzle).header("Authorization", participant.bearer())
					.header("X-Play-Session", fakeSession).contentType(MediaType.APPLICATION_JSON).content("{\"answers\":[{\"entryId\":" + d.entryIds.get(0) + ",\"answer\":\"abc\"}]}"))
					.andExpect(status().isForbidden());
			mvc.perform(post("/api/puzzles/{id}/reveal", puzzle).header("Authorization", participant.bearer())
					.header("X-Play-Session", fakeSession)).andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("BATTLE_IN_PROGRESS"));
			mvc.perform(get("/api/words/{id}", 1).header("Authorization", participant.bearer()).header("X-Play-Session", fakeSession))
					.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BATTLE_IN_PROGRESS"));
		}
		mvc.perform(get("/api/puzzles/{id}", puzzle).header("Authorization", d.bobT.bearer())).andExpect(status().isOk());
		mvc.perform(post("/api/puzzles/{id}/start", puzzle).header("Authorization", other.bearer())).andExpect(status().isOk());
		mvc.perform(post("/api/puzzles/{id}/start", puzzle)).andExpect(status().isOk());
	}

	@Test
	void theRestrictionEndsWithTheMatchAndWhenAPlayerLeaves() throws Exception {
		Duel d = startedDuel();
		d.bob.send("/app/rooms/" + d.code + "/leave", null);
		event(d.host, d.code, "LEAVE");
		mvc.perform(post("/api/puzzles/{id}/start", d.puzzleId).header("Authorization", d.bobT.bearer())).andExpect(status().isOk());
		mvc.perform(post("/api/puzzles/{id}/start", d.puzzleId).header("Authorization", d.hostT.bearer())).andExpect(status().isForbidden());

		clock().advance(Duration.ofSeconds(60));
		event(d.host, d.code, "END");

		mvc.perform(post("/api/puzzles/{id}/start", d.puzzleId).header("Authorization", d.hostT.bearer())).andExpect(status().isOk());
	}

	@Test
	void theLobbyDoesNotBlockLearningAids() throws Exception {
		Tokens hostTokens = guest("대기방장");
		String code = createRoom(hostTokens, 60);
		join(hostTokens, code);

		mvc.perform(post("/api/puzzles/{id}/start", 1).header("Authorization", hostTokens.bearer())).andExpect(status().isOk());
	}

	// ---------- 게스트가 회원이 되면 기록이 따라간다 ----------

	@Test
	void aGuestWhoBecomesAMemberKeepsTheirMatchHistory() throws Exception {
		Duel d = startedDuel();
		d.submitCorrect(d.bob, d.entryIds.get(0));
		d.bob.next("/user/queue/submit");
		clock().advance(Duration.ofSeconds(60));
		event(d.host, d.code, "END");
		assertThat(participants.findByOwnerTypeAndOwnerId(OwnerType.GUEST, d.bobT.ownerId())).hasSize(1);

		MvcResult signup = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", d.bobT.bearer())
				.content("{\"email\":\"mt" + UUID.randomUUID().toString().substring(0, 8)
						+ "@example.com\",\"password\":\"secret123\",\"nickname\":\"이전밥\",\"guestId\":\"" + d.bobT.ownerId() + "\"}"))
				.andExpect(status().isCreated()).andReturn();
		Tokens member = AuthTestClient.read(signup);

		assertThat(participants.findByOwnerTypeAndOwnerId(OwnerType.GUEST, d.bobT.ownerId())).isEmpty();
		assertThat(participants.findByOwnerTypeAndOwnerId(OwnerType.MEMBER, member.ownerId())).singleElement()
				.satisfies(r -> assertThat(r.getNickname()).isEqualTo("대결밥아"));
	}
}
