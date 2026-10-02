package crossword2.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import crossword2.auth.AuthTestClient.Tokens;

/** 시간을 움직여 access/refresh 토큰의 만료를 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthExpiryTest.ClockConfig.class)
class AuthExpiryTest {

	@TestConfiguration
	static class ClockConfig {

		@Bean
		@Primary
		MutableClock testClock() {
			return new MutableClock(Instant.now());
		}
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	Clock clock;

	private MutableClock clock() {
		return (MutableClock) clock;
	}

	private ResultActions refresh(String token) throws Exception {
		return mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + token + "\"}"));
	}

	@Test
	void expiredAccessTokenIsRejectedAndRefreshGivesAWorkingOne() throws Exception {
		Tokens tokens = AuthTestClient.guest(mvc, "만료");
		mvc.perform(get("/api/me").header("Authorization", tokens.bearer())).andExpect(status().isOk());

		clock().advance(Duration.ofMinutes(31));

		mvc.perform(get("/api/me").header("Authorization", tokens.bearer())).andExpect(status().isUnauthorized());
		String body = refresh(tokens.refreshToken()).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		String renewed = JsonPath.read(body, "$.accessToken");
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + renewed)).andExpect(status().isOk());
	}

	@Test
	void guestRefreshTokenLivesNinetyDays() throws Exception {
		Tokens tokens = AuthTestClient.guest(mvc, "삼개월");

		clock().advance(Duration.ofDays(89));
		String renewed = JsonPath.read(refresh(tokens.refreshToken()).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString(), "$.refreshToken");

		clock().advance(Duration.ofDays(91));
		refresh(renewed).andExpect(status().isUnauthorized());
	}

	@Test
	void memberRefreshTokenLivesThirtyDays() throws Exception {
		Tokens tokens = AuthTestClient.signup(mvc, "exp" + UUID.randomUUID().toString().substring(0, 8)
				+ "@example.com", "secret123", "한달");

		clock().advance(Duration.ofDays(31));

		refresh(tokens.refreshToken()).andExpect(status().isUnauthorized());
		assertThat(tokens.refreshToken()).isNotBlank();
	}
}
