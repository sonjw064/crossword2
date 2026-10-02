package crossword2.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/** 인증 API를 호출해 토큰을 얻는 테스트 도우미. */
public final class AuthTestClient {

	public record Tokens(String accessToken, String refreshToken, String ownerId) {

		public String bearer() {
			return "Bearer " + accessToken;
		}
	}

	private AuthTestClient() {
	}

	public static Tokens guest(MockMvc mvc, String nickname) throws Exception {
		return read(mvc.perform(post("/api/auth/guest").contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"" + nickname + "\"}")).andReturn());
	}

	public static Tokens signup(MockMvc mvc, String email, String password, String nickname) throws Exception {
		return read(mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"nickname\":\"" + nickname + "\"}"))
				.andReturn());
	}

	public static Tokens read(MvcResult result) throws Exception {
		String body = result.getResponse().getContentAsString();
		if (result.getResponse().getStatus() >= 300) {
			throw new IllegalStateException("auth call failed: " + result.getResponse().getStatus() + " " + body);
		}
		return new Tokens(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"),
				JsonPath.read(body, "$.ownerId"));
	}
}
