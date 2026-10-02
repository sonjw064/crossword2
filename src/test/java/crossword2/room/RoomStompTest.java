package crossword2.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.WebSocketHttpHeaders;

import com.jayway.jsonpath.JsonPath;

import crossword2.auth.AuthTestClient;
import crossword2.auth.AuthTestClient.Tokens;

/** 실제 포트에서 웹소켓(STOMP)으로 대기실을 사용하는 통합 테스트. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class RoomStompTest {

	@Value("${local.server.port}")
	int port;

	@Autowired
	MockMvc mvc;

	private final List<StompTestClient> opened = new ArrayList<>();

	@AfterEach
	void closeSockets() {
		opened.forEach(StompTestClient::close);
	}

	// ---------- 도우미 ----------

	private String createRoom(Tokens host, int maxPlayers) throws Exception {
		String body = mvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", host.bearer())
				.content("{\"difficulty\":\"EASY\",\"size\":7,\"timeLimitSec\":300,\"maxPlayers\":" + maxPlayers + ",\"mode\":\"RACE\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.code");
	}

	/** 연결하고 개인 큐와 방 토픽을 구독한다(입장은 하지 않는다). */
	private StompTestClient open(Tokens who, String code) throws Exception {
		StompTestClient socket = StompTestClient.connect(port, who.accessToken());
		opened.add(socket);
		socket.subscribe("/user/queue/me");
		socket.subscribe("/user/queue/errors");
		socket.subscribe("/topic/rooms/" + code);
		return socket;
	}

	private StompTestClient join(Tokens who, String code) throws Exception {
		StompTestClient socket = open(who, code);
		socket.send("/app/rooms/" + code + "/join", null);
		assertThat(socket.next("/user/queue/me")).as("입장 응답").isNotNull();
		return socket;
	}

	private static String topic(String code) {
		return "/topic/rooms/" + code;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> players(Map<String, Object> event) {
		return (List<Map<String, Object>>) ((Map<String, Object>) event.get("room")).get("players");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> room(Map<String, Object> event) {
		return (Map<String, Object>) event.get("room");
	}

	private Map<String, Object> event(StompTestClient socket, String code, String type) throws Exception {
		return socket.nextMatching(topic(code), m -> type.equals(m.get("type")));
	}

	private Tokens guest(String nickname) throws Exception {
		return AuthTestClient.guest(mvc, nickname);
	}

	// ---------- 연결과 인증 ----------

	@Test
	void connectingWithoutAValidTokenIsRefused() throws Exception {
		Tokens valid = guest("연결손님");

		assertThatThrownBy(() -> StompTestClient.connect(port, null)).isNotNull();
		assertThatThrownBy(() -> StompTestClient.connect(port, "garbage")).isNotNull();
		assertThatThrownBy(() -> StompTestClient.connect(port, valid.accessToken().substring(0, 40))).isNotNull();

		try (StompTestClient ok = StompTestClient.connect(port, valid.accessToken())) {
			assertThat(ok.isConnected()).isTrue();
		}
	}

	@Test
	void aMigratedGuestsTokenCannotConnectAnymore() throws Exception {
		Tokens guest = guest("이전된연결손님");
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).header("Authorization", guest.bearer())
				.content("{\"email\":\"ws" + UUID.randomUUID().toString().substring(0, 8)
						+ "@example.com\",\"password\":\"secret123\",\"nickname\":\"이전회원\",\"guestId\":\"" + guest.ownerId() + "\"}"))
				.andExpect(status().isCreated());

		assertThatThrownBy(() -> StompTestClient.connect(port, guest.accessToken())).isNotNull();
	}

	@Test
	void pagesFromAnotherOriginCannotOpenTheSocket() throws Exception {
		Tokens valid = guest("출처손님");
		WebSocketHttpHeaders evil = new WebSocketHttpHeaders();
		evil.setOrigin("http://evil.example");

		assertThatThrownBy(() -> StompTestClient.connect(port, valid.accessToken(), evil)).isNotNull();

		WebSocketHttpHeaders same = new WebSocketHttpHeaders();
		same.setOrigin("http://localhost:" + port);
		try (StompTestClient ok = StompTestClient.connect(port, valid.accessToken(), same)) {
			assertThat(ok.isConnected()).isTrue();
		}
	}

	// ---------- 입장과 방송 ----------

	@Test
	void theHostAndAGuestJoinAndBothSeeEachOther() throws Exception {
		Tokens hostTokens = guest("방장");
		Tokens bobTokens = guest("밥아");
		String code = createRoom(hostTokens, 4);

		StompTestClient host = join(hostTokens, code);
		StompTestClient bob = open(bobTokens, code);
		bob.send("/app/rooms/" + code + "/join", null);

		Map<String, Object> ack = bob.next("/user/queue/me");
		Map<String, Object> hostSees = event(host, code, "JOIN");
		Map<String, Object> bobSees = event(bob, code, "JOIN");

		assertThat(ack.get("playerId")).isEqualTo(2);
		assertThat(ack.get("rejoined")).isEqualTo(false);
		assertThat(players(hostSees)).extracting(p -> p.get("nickname")).containsExactly("방장", "밥아");
		assertThat(players(bobSees)).hasSize(2);
		assertThat(players(hostSees)).filteredOn(p -> Boolean.TRUE.equals(p.get("host"))).hasSize(1);
		assertThat(players(hostSees)).allMatch(p -> Boolean.TRUE.equals(p.get("connected")));
	}

	@Test
	void theHighestVersionAmongBroadcastsIsAlwaysTheCurrentRoom() throws Exception {
		// 명령은 여러 스레드에서 처리되어 방송이 뒤바뀌어 도착할 수 있다. 그래서 클라이언트는 version이 가장 높은 것을 믿는다.
		Tokens hostTokens = guest("버전방장");
		Tokens bobTokens = guest("버전밥아");
		String code = createRoom(hostTokens, 3);
		StompTestClient host = join(hostTokens, code);
		StompTestClient bob = join(bobTokens, code);

		bob.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
		bob.send("/app/rooms/" + code + "/ready", Map.of("ready", false));
		bob.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
		long highest = -1;
		Map<String, Object> newest = null;
		for (int i = 0; i < 5; i++) {
			Map<String, Object> m = host.next(topic(code), 1500);
			if (m != null && ((Number) room(m).get("version")).longValue() >= highest) {
				highest = ((Number) room(m).get("version")).longValue();
				newest = m;
			}
		}
		bob.send("/app/rooms/" + code + "/sync", null);
		// join 응답이 먼저 큐에 남아 있을 수 있으므로 가장 마지막 응답을 본다
		Map<String, Object> current = bob.nextMatching("/user/queue/me", m -> true);
		Map<String, Object> latest = current;
		for (Map<String, Object> m; (m = bob.next("/user/queue/me", 300)) != null;) {
			latest = m;
		}

		assertThat(newest).isNotNull();
		assertThat(room(newest).get("version")).isEqualTo(room(latest).get("version"));
		assertThat(players(newest)).isEqualTo(players(latest));
	}

	@Test
	void broadcastsNeverRevealAccountIdsOrTokens() throws Exception {
		Tokens hostTokens = guest("비밀방장");
		Tokens bobTokens = guest("비밀밥");
		String code = createRoom(hostTokens, 3);
		StompTestClient host = join(hostTokens, code);
		StompTestClient bob = join(bobTokens, code);
		bob.send("/app/rooms/" + code + "/ready", Map.of("ready", true));

		StringBuilder everything = new StringBuilder();
		for (int i = 0; i < 4; i++) {
			Map<String, Object> m = host.next(topic(code), 1500);
			if (m != null) {
				everything.append(m);
			}
		}
		everything.append(bob.next("/user/queue/me", 200));

		assertThat(everything.toString()).doesNotContain(hostTokens.ownerId()).doesNotContain(bobTokens.ownerId())
				.doesNotContain(hostTokens.accessToken()).doesNotContain("GUEST");
	}

	@Test
	void joiningAgainWithANewConnectionIsAReconnect() throws Exception {
		Tokens hostTokens = guest("재접속방장");
		Tokens bobTokens = guest("재접속밥");
		String code = createRoom(hostTokens, 3);
		StompTestClient host = join(hostTokens, code);
		StompTestClient bob = join(bobTokens, code);
		bob.disconnect();
		Map<String, Object> dropped = event(host, code, "DISCONNECT");
		assertThat(players(dropped)).filteredOn(p -> "재접속밥".equals(p.get("nickname")))
				.allMatch(p -> Boolean.FALSE.equals(p.get("connected")));

		StompTestClient again = open(bobTokens, code);
		again.send("/app/rooms/" + code + "/join", null);
		Map<String, Object> ack = again.next("/user/queue/me");
		Map<String, Object> back = event(host, code, "RECONNECT");

		assertThat(ack.get("playerId")).as("같은 자리").isEqualTo(2);
		assertThat(ack.get("rejoined")).isEqualTo(true);
		assertThat(players(back)).hasSize(2).allMatch(p -> Boolean.TRUE.equals(p.get("connected")));
	}

	// ---------- 준비/시작/설정/퇴장 ----------

	@Test
	void readyAndStartFollowTheLobbyRules() throws Exception {
		Tokens hostTokens = guest("시작방장");
		Tokens bobTokens = guest("시작밥");
		String code = createRoom(hostTokens, 3);
		StompTestClient host = join(hostTokens, code);
		StompTestClient bob = join(bobTokens, code);

		host.send("/app/rooms/" + code + "/start", null);
		assertThat(host.next("/user/queue/errors").get("code")).isEqualTo("NOT_ALL_READY");

		bob.send("/app/rooms/" + code + "/start", null);
		assertThat(bob.next("/user/queue/errors").get("code")).isEqualTo("NOT_HOST");

		bob.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
		Map<String, Object> ready = event(host, code, "READY");
		assertThat(players(ready)).filteredOn(p -> "시작밥".equals(p.get("nickname"))).allMatch(p -> Boolean.TRUE.equals(p.get("ready")));

		host.send("/app/rooms/" + code + "/start", null);
		Map<String, Object> started = event(bob, code, "START");

		assertThat(room(started).get("status")).isEqualTo("PLAYING");
		assertThat(room(started).get("puzzleId")).isNotNull();
	}

	@Test
	void onlyTheHostCanChangeSettingsAndEveryoneIsToldWhenTheyChange() throws Exception {
		Tokens hostTokens = guest("설정방장");
		Tokens bobTokens = guest("설정밥");
		String code = createRoom(hostTokens, 4);
		StompTestClient host = join(hostTokens, code);
		StompTestClient bob = join(bobTokens, code);
		Map<String, Object> next = Map.of("difficulty", "MEDIUM", "size", 7, "timeLimitSec", 600, "maxPlayers", 3, "mode", "RACE");

		bob.send("/app/rooms/" + code + "/settings", next);
		assertThat(bob.next("/user/queue/errors").get("code")).isEqualTo("NOT_HOST");

		host.send("/app/rooms/" + code + "/settings", next);
		Map<String, Object> changed = event(bob, code, "SETTINGS");
		@SuppressWarnings("unchecked")
		Map<String, Object> settings = (Map<String, Object>) room(changed).get("settings");
		assertThat(settings.get("difficulty")).isEqualTo("MEDIUM");
		assertThat(settings.get("maxPlayers")).isEqualTo(3);
	}

	@Test
	void invalidSettingsAndMalformedMessagesGetClearErrorsOnlyForTheSender() throws Exception {
		Tokens hostTokens = guest("오류방장");
		String code = createRoom(hostTokens, 4);
		StompTestClient host = join(hostTokens, code);

		host.send("/app/rooms/" + code + "/settings",
				Map.of("difficulty", "EASY", "size", 99, "timeLimitSec", 600, "maxPlayers", 3, "mode", "RACE"));
		assertThat(host.next("/user/queue/errors").get("code")).isEqualTo("SETTINGS_INVALID");

		host.send("/app/rooms/" + code + "/settings",
				Map.of("difficulty", "EASY", "topic", "no-such-topic", "size", 7, "timeLimitSec", 600, "maxPlayers", 3, "mode", "RACE"));
		assertThat(host.next("/user/queue/errors").get("code")).isEqualTo("NO_PUZZLE_AVAILABLE");

		host.send("/app/rooms/" + code + "/ready", "this is not a command");
		assertThat(host.next("/user/queue/errors").get("code")).isEqualTo("MALFORMED_REQUEST");
		assertThat(host.isConnected()).as("오류가 나도 연결은 유지된다").isTrue();
	}

	@Test
	void whenTheHostLeavesTheNextPlayerBecomesHost() throws Exception {
		Tokens hostTokens = guest("떠나는방장");
		Tokens bobTokens = guest("이어받는밥");
		String code = createRoom(hostTokens, 3);
		StompTestClient host = join(hostTokens, code);
		StompTestClient bob = join(bobTokens, code);

		host.send("/app/rooms/" + code + "/leave", null);
		Map<String, Object> changed = event(bob, code, "HOST_CHANGED");
		Map<String, Object> left = event(bob, code, "LEAVE");

		assertThat(players(changed)).hasSize(1);
		assertThat(players(changed).get(0).get("nickname")).isEqualTo("이어받는밥");
		assertThat(players(changed).get(0).get("host")).isEqualTo(true);
		assertThat(players(left)).hasSize(1);
	}

	@Test
	void whenTheLastPlayerLeavesTheRoomIsClosedAndGone() throws Exception {
		Tokens hostTokens = guest("마지막방장");
		String code = createRoom(hostTokens, 2);
		StompTestClient host = join(hostTokens, code);

		host.send("/app/rooms/" + code + "/leave", null);
		Map<String, Object> closed = event(host, code, "CLOSED");

		assertThat(players(closed)).isEmpty();
		StompTestClient later = StompTestClient.connect(port, guest("늦은손님").accessToken());
		opened.add(later);
		later.subscribe(topic(code));
		assertThat(later.awaitClosed()).as("이미 없는 방의 토픽은 구독할 수 없다").isTrue();
	}

	@Test
	void syncReturnsTheCurrentRoomToTheAskerOnlyAndBroadcastsNothing() throws Exception {
		Tokens hostTokens = guest("동기방장");
		Tokens bobTokens = guest("동기밥아");
		String code = createRoom(hostTokens, 3);
		StompTestClient host = join(hostTokens, code);
		StompTestClient bob = join(bobTokens, code);
		event(host, code, "JOIN");

		bob.send("/app/rooms/" + code + "/sync", null);
		Map<String, Object> snapshot = bob.next("/user/queue/me");

		assertThat(snapshot.get("playerId")).isEqualTo(2);
		assertThat(players(snapshot)).hasSize(2);
		assertThat(host.next(topic(code), 300)).as("sync는 방송하지 않는다").isNull();

		StompTestClient outsider = open(guest("동기외부인"), code);
		outsider.send("/app/rooms/" + code + "/sync", null);
		assertThat(outsider.next("/user/queue/errors").get("code")).isEqualTo("NOT_IN_ROOM");
		assertThat(outsider.next("/user/queue/me", 300)).isNull();
	}

	// ---------- 한 사람 한 방, 없는 방, 참가자가 아닌 사람 ----------

	@Test
	void aUserInOneRoomCannotJoinAnother() throws Exception {
		Tokens hostA = guest("A방장");
		Tokens hostB = guest("B방장");
		Tokens traveler = guest("여행자");
		String codeA = createRoom(hostA, 3);
		String codeB = createRoom(hostB, 3);
		join(traveler, codeA);

		StompTestClient socket = open(traveler, codeB);
		socket.send("/app/rooms/" + codeB + "/join", null);

		Map<String, Object> error = socket.next("/user/queue/errors");
		assertThat(error.get("code")).isEqualTo("ALREADY_IN_ROOM");
		assertThat((String) error.get("message")).contains(codeA);
	}

	@Test
	void commandsOnUnknownRoomsOrFromOutsidersAreRejectedWithoutAffectingTheRoom() throws Exception {
		Tokens hostTokens = guest("보호방장");
		String code = createRoom(hostTokens, 3);
		StompTestClient host = join(hostTokens, code);
		event(host, code, "RECONNECT");
		StompTestClient outsider = open(guest("외부인"), code); // 대기 중인 방은 구독할 수 있다

		outsider.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
		assertThat(outsider.next("/user/queue/errors").get("code")).isEqualTo("NOT_IN_ROOM");
		outsider.send("/app/rooms/" + code + "/start", null);
		assertThat(outsider.next("/user/queue/errors").get("code")).isEqualTo("NOT_IN_ROOM");
		outsider.send("/app/rooms/ZZZZZZ/ready", Map.of("ready", true));
		assertThat(outsider.next("/user/queue/errors").get("code")).isEqualTo("ROOM_NOT_FOUND");
		outsider.send("/app/rooms/not-a-code/join", null);
		assertThat(outsider.next("/user/queue/errors").get("code")).isEqualTo("ROOM_NOT_FOUND");

		assertThat(host.next(topic(code), 300)).as("방 참가자에게는 아무 변화도 방송되지 않는다").isNull();
	}

	// ---------- 구독/전송 제한 ----------

	@Test
	void subscribingToOtherDestinationsOrSendingToTopicsClosesTheConnection() throws Exception {
		Tokens who = guest("규칙손님");

		for (String forbidden : List.of("/topic/anything", "/topic/rooms/ZZZZZZ", "/queue/me", "/user/queue/other")) {
			StompTestClient socket = StompTestClient.connect(port, who.accessToken());
			opened.add(socket);
			socket.subscribe(forbidden);
			assertThat(socket.awaitClosed()).as("구독 거부: " + forbidden).isTrue();
		}
		for (String forbidden : List.of("/topic/rooms/ABC234", "/app/other", "/queue/me")) {
			StompTestClient socket = StompTestClient.connect(port, who.accessToken());
			opened.add(socket);
			socket.send(forbidden, Map.of("x", 1));
			assertThat(socket.awaitClosed()).as("전송 거부: " + forbidden).isTrue();
		}
	}

	@Test
	void aStrangerCannotWatchAMatchThatIsAlreadyRunning() throws Exception {
		Tokens hostTokens = guest("진행방장");
		Tokens bobTokens = guest("진행밥");
		String code = createRoom(hostTokens, 3);
		StompTestClient host = join(hostTokens, code);
		StompTestClient bob = join(bobTokens, code);
		bob.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
		event(host, code, "READY");
		host.send("/app/rooms/" + code + "/start", null);
		event(host, code, "START");

		StompTestClient stranger = StompTestClient.connect(port, guest("구경꾼").accessToken());
		opened.add(stranger);
		stranger.subscribe(topic(code));

		assertThat(stranger.awaitClosed()).as("경기 중인 방은 참가자만 구독할 수 있다").isTrue();
		StompTestClient participant = StompTestClient.connect(port, hostTokens.accessToken());
		opened.add(participant);
		participant.subscribe("/user/queue/me");
		participant.subscribe(topic(code));
		assertThat(participant.next("/user/queue/me", 300)).isNull();
		assertThat(participant.isConnected()).isTrue();
	}

	@Test
	void noOneNewCanJoinOnceTheMatchHasStarted() throws Exception {
		Tokens hostTokens = guest("시작뒤방장");
		Tokens bobTokens = guest("시작뒤밥");
		String code = createRoom(hostTokens, 4);
		StompTestClient host = join(hostTokens, code);
		StompTestClient bob = join(bobTokens, code);
		bob.send("/app/rooms/" + code + "/ready", Map.of("ready", true));
		event(host, code, "READY");
		host.send("/app/rooms/" + code + "/start", null);
		event(host, code, "START");

		StompTestClient late = open(guest("지각생"), code);
		// 경기 중이므로 구독부터 거부되어 연결이 닫힌다
		assertThat(late.awaitClosed()).isTrue();
	}

	// ---------- 동시 입장 ----------

	@Test
	void concurrentJoinsNeverExceedTheRoomCapacity() throws Exception {
		Tokens hostTokens = guest("정원방장");
		String code = createRoom(hostTokens, 3);
		join(hostTokens, code);
		int challengers = 8;
		List<StompTestClient> sockets = new ArrayList<>();
		for (int i = 0; i < challengers; i++) {
			sockets.add(open(guest("도전자" + i), code));
		}
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(challengers);
		try {
			for (StompTestClient socket : sockets) {
				pool.submit(() -> {
					go.await();
					socket.send("/app/rooms/" + code + "/join", null);
					return null;
				});
			}
			go.countDown();
		} finally {
			pool.shutdown();
		}

		int acks = 0;
		int full = 0;
		for (StompTestClient socket : sockets) {
			if (socket.next("/user/queue/me", 3000) != null) {
				acks++;
			} else {
				Map<String, Object> error = socket.next("/user/queue/errors", 3000);
				if (error != null && "ROOM_FULL".equals(error.get("code"))) {
					full++;
				}
			}
		}

		assertThat(acks).as("정원 3명 중 방장을 뺀 2자리만 채워진다").isEqualTo(2);
		assertThat(full).isEqualTo(challengers - 2);
	}

	// ---------- 게스트가 회원으로 이전되면 방에서 나간다 ----------

	@Test
	void whenAGuestBecomesAMemberTheyAreRemovedFromTheirRoom() throws Exception {
		Tokens hostTokens = guest("이전방장");
		Tokens travelerTokens = guest("이전여행자");
		String code = createRoom(hostTokens, 3);
		StompTestClient host = join(hostTokens, code);
		join(travelerTokens, code);
		event(host, code, "JOIN");

		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).header("Authorization", travelerTokens.bearer())
				.content("{\"email\":\"rk" + UUID.randomUUID().toString().substring(0, 8)
						+ "@example.com\",\"password\":\"secret123\",\"nickname\":\"이전된회원\",\"guestId\":\"" + travelerTokens.ownerId() + "\"}"))
				.andExpect(status().isCreated());

		Map<String, Object> left = event(host, code, "LEAVE");
		assertThat(players(left)).extracting(p -> p.get("nickname")).containsExactly("이전방장");
	}
}
