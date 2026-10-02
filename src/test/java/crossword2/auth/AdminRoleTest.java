package crossword2.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import crossword2.auth.AuthTestClient.Tokens;

/** 관리자 권한은 토큰의 claim이 아니라 현재 DB의 role로 판단한다. (관리자 API 자체는 4단계라 /api/admin/**에는 핸들러가 없다.) */
@SpringBootTest
@AutoConfigureMockMvc
class AdminRoleTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	private String registerAndPromote(String email) throws Exception {
		AuthTestClient.signup(mvc, email, "secret123", "관리자");
		User user = users.findByEmail(email).orElseThrow();
		user.changeRole(Role.ADMIN);
		users.save(user);
		return email;
	}

	private Tokens login(String email) throws Exception {
		return AuthTestClient.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"secret123\"}")).andReturn());
	}

	@Test
	void adminTokenPassesAuthorizationWhileTheUserIsStillAdmin() throws Exception {
		String email = registerAndPromote("admin" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
		Tokens admin = login(email);

		// 인가를 통과하면 핸들러가 없어 404, 거부되면 403
		mvc.perform(get("/api/admin/anything").header("Authorization", admin.bearer())).andExpect(status().isNotFound());
	}

	@Test
	void revokingAdminRoleTakesEffectImmediatelyForExistingTokens() throws Exception {
		String email = registerAndPromote("revoke" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
		Tokens admin = login(email);
		mvc.perform(get("/api/admin/anything").header("Authorization", admin.bearer())).andExpect(status().isNotFound());

		User user = users.findByEmail(email).orElseThrow();
		user.changeRole(Role.USER);
		users.save(user);

		mvc.perform(get("/api/admin/anything").header("Authorization", admin.bearer())).andExpect(status().isForbidden());
		mvc.perform(get("/api/me").header("Authorization", admin.bearer())).andExpect(status().isOk());
	}

	@Test
	void aDeletedAdminLosesAccessToo() throws Exception {
		String email = registerAndPromote("gone" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
		Tokens admin = login(email);

		users.delete(users.findByEmail(email).orElseThrow());

		mvc.perform(get("/api/admin/anything").header("Authorization", admin.bearer())).andExpect(status().isForbidden());
	}
}
