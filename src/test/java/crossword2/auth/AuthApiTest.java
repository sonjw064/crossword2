package crossword2.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

import crossword2.auth.AuthTestClient.Tokens;

@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTest {

	private static final String PASSWORD = "secret123";

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository userRepository;

	@Autowired
	RefreshTokenRepository refreshTokenRepository;

	private static String unique(String prefix) {
		return prefix + UUID.randomUUID().toString().substring(0, 8);
	}

	private ResultActions postJson(String url, String json) throws Exception {
		return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions login(String email, String password) throws Exception {
		return postJson("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
	}

	private ResultActions refresh(String token) throws Exception {
		return postJson("/api/auth/refresh", "{\"refreshToken\":\"" + token + "\"}");
	}

	// ---- 게스트 ----

	@Test
	void guestGetsAnIdAndATokenThatWorksOnMe() throws Exception {
		String body = postJson("/api/auth/guest", "{\"nickname\":\"손님 1\"}").andExpect(status().isCreated())
				.andExpect(jsonPath("$.ownerType").value("GUEST"))
				.andExpect(jsonPath("$.nickname").value("손님 1"))
				.andExpect(jsonPath("$.role").doesNotExist())
				.andExpect(jsonPath("$.expiresIn").value(1800))
				.andReturn().getResponse().getContentAsString();
		String guestId = JsonPath.read(body, "$.ownerId");
		assertThat(UUID.fromString(guestId)).isNotNull();

		mvc.perform(get("/api/me").header("Authorization", "Bearer " + JsonPath.read(body, "$.accessToken")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ownerType").value("GUEST"))
				.andExpect(jsonPath("$.id").value(guestId))
				.andExpect(jsonPath("$.nickname").value("손님 1"))
				.andExpect(jsonPath("$.email").doesNotExist());
	}

	@ParameterizedTest
	@ValueSource(strings = { "a", " ab", "ab ", "<script>", "a@b.c", "abcdefghijklmnopqrstu", "a\\\\nb" })
	void rejectsInvalidNicknames(String nickname) throws Exception {
		postJson("/api/auth/guest", "{\"nickname\":\"" + nickname + "\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "홍길동", "Alex_01", "a b", "ab", "하-나" })
	void acceptsReasonableNicknames(String nickname) throws Exception {
		postJson("/api/auth/guest", "{\"nickname\":\"" + nickname + "\"}").andExpect(status().isCreated());
	}

	// ---- 회원가입 ----

	@Test
	void signupCreatesAMemberAndNeverReturnsThePassword() throws Exception {
		String email = unique("kim") + "@example.com";

		String body = postJson("/api/auth/signup",
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"nickname\":\"김철수\"}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.ownerType").value("MEMBER"))
				.andExpect(jsonPath("$.role").value("USER"))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(PASSWORD).doesNotContainIgnoringCase("passwordHash");

		mvc.perform(get("/api/me").header("Authorization", "Bearer " + JsonPath.read(body, "$.accessToken")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.role").value("USER"));
	}

	@Test
	void passwordIsStoredAsABcryptHash() throws Exception {
		String email = unique("hash") + "@example.com";
		AuthTestClient.signup(mvc, email, PASSWORD, "해시");

		User user = userRepository.findByEmail(email).orElseThrow();

		assertThat(user.getPasswordHash()).startsWith("$2").isNotEqualTo(PASSWORD);
	}

	@Test
	void duplicateEmailIsRejectedIgnoringCase() throws Exception {
		String email = unique("dup") + "@example.com";
		AuthTestClient.signup(mvc, email, PASSWORD, "첫째");

		postJson("/api/auth/signup", "{\"email\":\"" + email.toUpperCase() + "\",\"password\":\"" + PASSWORD
				+ "\",\"nickname\":\"둘째\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "short1", "onlyletters", "12345678", "", "a1bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" })
	void rejectsPasswordsThatBreakThePolicy(String password) throws Exception {
		postJson("/api/auth/signup", "{\"email\":\"" + unique("pw") + "@example.com\",\"password\":\"" + password
				+ "\",\"nickname\":\"비번\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void rejectsPasswordsLongerThanBcryptAllowsInBytes() throws Exception {
		String password = "가나다라마바사아자차카타파하거너더러머버고노도로" + "a1"; // 24자 = 72바이트 + 2 = 74바이트 (BCrypt 한도 72바이트 초과)

		postJson("/api/auth/signup", "{\"email\":\"" + unique("long") + "@example.com\",\"password\":\"" + password
				+ "\",\"nickname\":\"긴비번\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "plainaddress", "no@", "@no.com", "" })
	void rejectsBadEmails(String email) throws Exception {
		postJson("/api/auth/signup", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
				+ "\",\"nickname\":\"메일\"}").andExpect(status().isBadRequest());
	}

	// ---- 로그인 ----

	@Test
	void loginWorksAndIgnoresEmailCase() throws Exception {
		String email = unique("login") + "@example.com";
		AuthTestClient.signup(mvc, email, PASSWORD, "로그인");

		login(email.toUpperCase(), PASSWORD).andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty())
				.andExpect(jsonPath("$.nickname").value("로그인"));
	}

	@Test
	void wrongPasswordAndUnknownEmailLookIdentical() throws Exception {
		String email = unique("who") + "@example.com";
		AuthTestClient.signup(mvc, email, PASSWORD, "누구");

		String wrongPassword = login(email, "wrongpass1").andExpect(status().isUnauthorized()).andReturn().getResponse()
				.getContentAsString();
		String unknownEmail = login(unique("nobody") + "@example.com", PASSWORD).andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();

		assertThat(wrongPassword).isEqualTo(unknownEmail).contains("INVALID_CREDENTIALS");
	}

	// ---- refresh / logout ----

	@Test
	void refreshRotatesTheTokenAndInvalidatesTheOldOne() throws Exception {
		Tokens first = AuthTestClient.guest(mvc, "회전");

		String body = refresh(first.refreshToken()).andExpect(status().isOk())
				.andExpect(jsonPath("$.ownerId").value(first.ownerId())).andReturn().getResponse().getContentAsString();
		String second = JsonPath.read(body, "$.refreshToken");

		assertThat(second).isNotEqualTo(first.refreshToken());
		refresh(first.refreshToken()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
	}

	@Test
	void reusingAnOldRefreshTokenRevokesTheWholeFamily() throws Exception {
		Tokens first = AuthTestClient.guest(mvc, "탈취");
		String second = JsonPath.read(refresh(first.refreshToken()).andReturn().getResponse().getContentAsString(),
				"$.refreshToken");

		refresh(first.refreshToken()).andExpect(status().isUnauthorized()); // 폐기된 토큰의 재사용 = 탈취 의심

		refresh(second).andExpect(status().isUnauthorized()); // 정상 사용자의 최신 토큰도 함께 폐기된다
	}

	@Test
	void refreshWithAnUnknownTokenIsRejected() throws Exception {
		refresh("definitely-not-a-token").andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
	}

	@Test
	void logoutRevokesTheRefreshToken() throws Exception {
		Tokens tokens = AuthTestClient.signup(mvc, unique("out") + "@example.com", PASSWORD, "로그아웃");

		postJson("/api/auth/logout", "{\"refreshToken\":\"" + tokens.refreshToken() + "\"}")
				.andExpect(status().isNoContent());

		refresh(tokens.refreshToken()).andExpect(status().isUnauthorized());
		postJson("/api/auth/logout", "{\"refreshToken\":\"unknown\"}").andExpect(status().isNoContent());
	}

	@Test
	void refreshTokensAreStoredOnlyAsHashes() throws Exception {
		Tokens tokens = AuthTestClient.guest(mvc, "해시토큰");

		assertThat(refreshTokenRepository.findByTokenHash(tokens.refreshToken())).isEmpty();
		assertThat(refreshTokenRepository.findByTokenHash(TokenService.hash(tokens.refreshToken()))).isPresent();
	}

	// ---- 접근 제어 ----

	@Test
	void meRequiresAValidToken() throws Exception {
		mvc.perform(get("/api/me")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		mvc.perform(get("/api/me").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void adminAreaIsForAdminsOnly() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "일반손님");
		Tokens member = AuthTestClient.signup(mvc, unique("adm") + "@example.com", PASSWORD, "일반회원");

		mvc.perform(get("/api/admin/words")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/admin/words").header("Authorization", guest.bearer())).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mvc.perform(get("/api/admin/words").header("Authorization", member.bearer())).andExpect(status().isForbidden());
	}

	@Test
	void anonymousPlayAndPublicEndpointsStayOpen() throws Exception {
		mvc.perform(get("/api/puzzles")).andExpect(status().isOk());
		mvc.perform(get("/api/puzzles/options")).andExpect(status().isOk());
		mvc.perform(get("/api/health")).andExpect(status().isOk());
		mvc.perform(get("/internal/secret.txt")).andExpect(status().isUnauthorized());
	}

	@Test
	void anInvalidTokenIsRejectedEvenOnPublicEndpoints() throws Exception {
		mvc.perform(get("/api/puzzles").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized());
	}

	@Test
	void responsesCarryASecurityPolicyHeader() throws Exception {
		mvc.perform(get("/api/health")).andExpect(header().string("Content-Security-Policy",
				Matchers.allOf(Matchers.containsString("default-src 'self'"),
						Matchers.containsString("frame-ancestors 'none'"))));
	}
}
