package crossword2.feedback;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import crossword2.auth.AuthTestClient;
import crossword2.auth.AuthTestClient.Tokens;

/** 스팸 방지: 작성자(ID)별, IP별 시간당 작성 횟수 제한. */
class FeedbackRateLimitTest {

	private static ResultActions submit(MockMvc mvc, Tokens caller) throws Exception {
		return mvc.perform(post("/api/feedback").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", caller.bearer())
				.content("{\"type\":\"GENERAL\",\"title\":\"제목\",\"content\":\"내용\"}"));
	}

	@Nested
	@SpringBootTest(properties = { "app.feedback.per-owner-per-hour=2", "app.feedback.per-ip-per-hour=1000" })
	@AutoConfigureMockMvc
	class PerAuthor {

		@Autowired
		MockMvc mvc;

		@Test
		void eachAuthorHasTheirOwnLimit() throws Exception {
			Tokens spammer = AuthTestClient.guest(mvc, "스팸손님");
			Tokens innocent = AuthTestClient.guest(mvc, "평범손님");

			submit(mvc, spammer).andExpect(status().isCreated());
			submit(mvc, spammer).andExpect(status().isCreated());
			submit(mvc, spammer).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));

			submit(mvc, innocent).andExpect(status().isCreated());
		}
	}

	@Nested
	@SpringBootTest(properties = { "app.feedback.per-owner-per-hour=1000", "app.feedback.per-ip-per-hour=3" })
	@AutoConfigureMockMvc
	class PerIp {

		@Autowired
		MockMvc mvc;

		@Test
		void aSingleAddressCannotSidestepTheLimitWithManyAccounts() throws Exception {
			for (int i = 0; i < 3; i++) {
				submit(mvc, AuthTestClient.guest(mvc, "여러계정" + i)).andExpect(status().isCreated());
			}

			submit(mvc, AuthTestClient.guest(mvc, "네번째계정")).andExpect(status().isTooManyRequests())
					.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
		}
	}
}
