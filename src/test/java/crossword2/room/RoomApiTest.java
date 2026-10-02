package crossword2.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import crossword2.auth.AuthTestClient;
import crossword2.auth.AuthTestClient.Tokens;

@SpringBootTest
@AutoConfigureMockMvc
class RoomApiTest {

	static final String VALID = "{\"difficulty\":\"EASY\",\"size\":7,\"timeLimitSec\":300,\"maxPlayers\":4,\"mode\":\"RACE\"}";

	@Autowired
	MockMvc mvc;

	private ResultActions create(Tokens caller, String json) throws Exception {
		var request = post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content(json);
		if (caller != null) {
			request = request.header("Authorization", caller.bearer());
		}
		return mvc.perform(request);
	}

	private static String uniqueEmail() {
		return "rm" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
	}

	private String codeOf(ResultActions created) throws Exception {
		return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.code");
	}

	// ---- 방 만들기 ----

	@Test
	void aGuestCanCreateARoomAndIsItsFirstPlayerAndHost() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "방장손님");

		create(guest, VALID).andExpect(status().isCreated())
				.andExpect(jsonPath("$.code").value(Matchers.matchesPattern("[" + RoomCodes.ALPHABET + "]{6}")))
				.andExpect(jsonPath("$.playerId").value(1))
				.andExpect(jsonPath("$.room.status").value("WAITING"))
				.andExpect(jsonPath("$.room.settings.maxPlayers").value(4))
				.andExpect(jsonPath("$.room.players.length()").value(1))
				.andExpect(jsonPath("$.room.players[0].nickname").value("방장손님"))
				.andExpect(jsonPath("$.room.players[0].host").value(true))
				.andExpect(jsonPath("$.room.players[0].connected").value(false))
				.andExpect(jsonPath("$.room.puzzleId").doesNotExist());
	}

	@Test
	void aMemberCanCreateARoomToo() throws Exception {
		Tokens member = AuthTestClient.signup(mvc, uniqueEmail(), "secret123", "방장회원");

		create(member, VALID).andExpect(status().isCreated()).andExpect(jsonPath("$.room.players[0].nickname").value("방장회원"));
	}

	@Test
	void creatingARoomRequiresLogin() throws Exception {
		create(null, VALID).andExpect(status().isUnauthorized());
	}

	@Test
	void topicAndTimeSettingsAreKept() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "주제손님");

		create(guest, "{\"difficulty\":\"MEDIUM\",\"topic\":\"animals\",\"size\":7,\"timeLimitSec\":900,\"maxPlayers\":8,\"mode\":\"RACE\"}")
				.andExpect(status().isCreated()).andExpect(jsonPath("$.room.settings.topic").value("animals"))
				.andExpect(jsonPath("$.room.settings.timeLimitSec").value(900))
				.andExpect(jsonPath("$.room.settings.difficulty").value("MEDIUM"));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"difficulty\":\"EASY\",\"size\":6,\"timeLimitSec\":300,\"maxPlayers\":4,\"mode\":\"RACE\"}",
			"{\"difficulty\":\"EASY\",\"size\":16,\"timeLimitSec\":300,\"maxPlayers\":4,\"mode\":\"RACE\"}",
			"{\"difficulty\":\"EASY\",\"size\":7,\"timeLimitSec\":59,\"maxPlayers\":4,\"mode\":\"RACE\"}",
			"{\"difficulty\":\"EASY\",\"size\":7,\"timeLimitSec\":3601,\"maxPlayers\":4,\"mode\":\"RACE\"}",
			"{\"difficulty\":\"EASY\",\"size\":7,\"timeLimitSec\":300,\"maxPlayers\":1,\"mode\":\"RACE\"}",
			"{\"difficulty\":\"EASY\",\"size\":7,\"timeLimitSec\":300,\"maxPlayers\":9,\"mode\":\"RACE\"}" })
	void outOfRangeSettingsAreRejected(String json) throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "범위손님");

		create(guest, json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SETTINGS_INVALID"));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"size\":7,\"timeLimitSec\":300,\"maxPlayers\":4,\"mode\":\"RACE\"}",
			"{\"difficulty\":\"EASY\",\"timeLimitSec\":300,\"maxPlayers\":4,\"mode\":\"RACE\"}",
			"{\"difficulty\":\"EASY\",\"size\":7,\"timeLimitSec\":300,\"maxPlayers\":4}",
			"{\"difficulty\":\"IMPOSSIBLE\",\"size\":7,\"timeLimitSec\":300,\"maxPlayers\":4,\"mode\":\"RACE\"}",
			"{\"difficulty\":\"EASY\",\"size\":\"big\",\"timeLimitSec\":300,\"maxPlayers\":4,\"mode\":\"RACE\"}",
			"not json", "{}" })
	void missingOrMalformedFieldsAreRejected(String json) throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "형식손님");

		create(guest, json).andExpect(status().isBadRequest());
	}

	@ParameterizedTest
	@ValueSource(strings = { "WORD_CLAIM", "COOP" })
	void modesThatAreNotImplementedYetAreRefused(String mode) throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "모드손님");

		create(guest, VALID.replace("RACE", mode)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MODE_NOT_AVAILABLE"));
	}

	@Test
	void settingsWithoutAnyMatchingPuzzleAreRefused() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "퍼즐없음손님");

		create(guest, "{\"difficulty\":\"EASY\",\"topic\":\"no-such-topic\",\"size\":7,\"timeLimitSec\":300,\"maxPlayers\":4,\"mode\":\"RACE\"}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NO_PUZZLE_AVAILABLE"));
		create(guest, "{\"difficulty\":\"EASY\",\"size\":15,\"timeLimitSec\":300,\"maxPlayers\":4,\"mode\":\"RACE\"}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NO_PUZZLE_AVAILABLE"));
	}

	@Test
	void oneUserCanOnlyHostOneRoomAtATime() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "한방손님");
		String first = codeOf(create(guest, VALID).andExpect(status().isCreated()));

		create(guest, VALID).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_IN_ROOM"))
				.andExpect(jsonPath("$.message").value(Matchers.containsString(first)));
	}

	// ---- 방 조회 ----

	@Test
	void anyLoggedInUserWhoKnowsTheCodeSeesTheSummary() throws Exception {
		Tokens host = AuthTestClient.guest(mvc, "조회방장");
		Tokens stranger = AuthTestClient.guest(mvc, "조회낯선이");
		String code = codeOf(create(host, VALID));

		mvc.perform(get("/api/rooms/{code}", code).header("Authorization", stranger.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.status").value("WAITING"))
				.andExpect(jsonPath("$.playerCount").value(1)).andExpect(jsonPath("$.joinable").value(true))
				.andExpect(jsonPath("$.you").doesNotExist());
		mvc.perform(get("/api/rooms/{code}", code).header("Authorization", host.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.you").value(1));
	}

	@Test
	void theCodeIsCaseInsensitiveWhenLookingARoomUp() throws Exception {
		Tokens host = AuthTestClient.guest(mvc, "대소문자방장");
		String code = codeOf(create(host, VALID));

		mvc.perform(get("/api/rooms/{code}", code.toLowerCase()).header("Authorization", host.bearer()))
				.andExpect(status().isOk());
	}

	@Test
	void unknownAndMalformedCodesLookExactlyTheSame() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "없는방손님");

		String unknown = mvc.perform(get("/api/rooms/{code}", "ZZZZZZ").header("Authorization", guest.bearer()))
				.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		String malformed = mvc.perform(get("/api/rooms/{code}", "not-a-code").header("Authorization", guest.bearer()))
				.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

		assertThat(unknown).isEqualTo(malformed).contains("ROOM_NOT_FOUND");
	}

	@Test
	void lookingARoomUpRequiresLogin() throws Exception {
		mvc.perform(get("/api/rooms/{code}", "ABC234")).andExpect(status().isUnauthorized());
	}

	@Test
	void roomResponsesNeverExposeAccountIdentifiers() throws Exception {
		Tokens host = AuthTestClient.guest(mvc, "비노출방장");

		String created = create(host, VALID).andReturn().getResponse().getContentAsString();
		String code = JsonPath.read(created, "$.code");
		String info = mvc.perform(get("/api/rooms/{code}", code).header("Authorization", host.bearer())).andReturn()
				.getResponse().getContentAsString();

		assertThat(created).doesNotContain(host.ownerId()).doesNotContain("GUEST").doesNotContain("MEMBER");
		assertThat(info).doesNotContain(host.ownerId());
	}

	// ---- 초대 링크 ----

	@Test
	void theInviteLinkOpensTheAppWithoutLogin() throws Exception {
		// MockMvc는 forward를 따라가지 않으므로 index.html로 전달되는지(forwardedUrl)를 확인한다
		mvc.perform(get("/room/{code}", "ABC234")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
		mvc.perform(get("/room/{code}", "whatever")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
		String page = mvc.perform(get("/index.html")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		assertThat(page).contains("<title>영어 십자말풀이</title>");
	}
}
