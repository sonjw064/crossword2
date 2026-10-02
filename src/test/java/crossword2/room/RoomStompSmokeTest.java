package crossword2.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Value;

import com.jayway.jsonpath.JsonPath;

import crossword2.auth.AuthTestClient;
import crossword2.auth.AuthTestClient.Tokens;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class RoomStompSmokeTest {

	@Value("${local.server.port}")
	int port;

	@Autowired
	MockMvc mvc;

	@Test
	void hostCreatesARoomAndJoinsOverTheSocket() throws Exception {
		Tokens host = AuthTestClient.guest(mvc, "방장손님");
		String body = mvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", host.bearer())
				.content("{\"difficulty\":\"EASY\",\"size\":7,\"timeLimitSec\":300,\"maxPlayers\":4,\"mode\":\"RACE\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String code = JsonPath.read(body, "$.code");

		try (StompTestClient socket = StompTestClient.connect(port, host.accessToken())) {
			socket.subscribe("/user/queue/me");
			socket.subscribe("/user/queue/errors");
			socket.subscribe("/topic/rooms/" + code);
			socket.send("/app/rooms/" + code + "/join", null);

			Map<String, Object> ack = socket.next("/user/queue/me");
			Map<String, Object> event = socket.next("/topic/rooms/" + code);

			assertThat(ack).isNotNull();
			assertThat(ack.get("playerId")).isEqualTo(1);
			assertThat(event).isNotNull();
			assertThat(event.get("type")).isEqualTo("RECONNECT");
		}
	}
}
