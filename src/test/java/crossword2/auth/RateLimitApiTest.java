package crossword2.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = { "app.rate-limit.login-per-minute=3", "app.rate-limit.guest-per-hour=2",
		"app.rate-limit.signup-per-hour=2" })
@AutoConfigureMockMvc
class RateLimitApiTest {

	@Autowired
	MockMvc mvc;

	@Test
	void guestCreationIsLimitedPerIp() throws Exception {
		for (int i = 0; i < 2; i++) {
			mvc.perform(post("/api/auth/guest").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"제한\"}"))
					.andExpect(status().isCreated());
		}

		mvc.perform(post("/api/auth/guest").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"제한\"}"))
				.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
	}

	@Test
	void signupIsLimitedPerIp() throws Exception {
		for (int i = 0; i < 2; i++) {
			mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
					.content("{\"email\":\"rl" + i + "@example.com\",\"password\":\"secret123\",\"nickname\":\"가입\"}"))
					.andExpect(status().isCreated());
		}

		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"rl9@example.com\",\"password\":\"secret123\",\"nickname\":\"가입\"}"))
				.andExpect(status().isTooManyRequests());
	}

	@Test
	void failedLoginsCountTowardsTheLimit() throws Exception {
		String body = "{\"email\":\"nobody@example.com\",\"password\":\"whatever1\"}";
		for (int i = 0; i < 3; i++) {
			mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isUnauthorized());
		}

		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
	}
}
