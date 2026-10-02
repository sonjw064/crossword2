package crossword2.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import crossword2.auth.AuthTestClient.Tokens;

/** 새 데이터 모델이 등록하는 GuestDataMigrator가 정확히 호출되고, 실패하면 이전 전체가 취소되는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(GuestMigratorExtensionTest.Config.class)
class GuestMigratorExtensionTest {

	static class RecordingMigrator implements GuestDataMigrator {

		final List<List<Owner>> calls = new CopyOnWriteArrayList<>();
		volatile boolean fail;

		@Override
		public void migrate(Owner guest, Owner member) {
			if (fail) {
				throw new IllegalStateException("boom");
			}
			calls.add(List.of(guest, member));
		}
	}

	@TestConfiguration
	static class Config {

		@Bean
		RecordingMigrator recordingMigrator() {
			return new RecordingMigrator();
		}
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	RecordingMigrator migrator;

	@Autowired
	UserRepository users;

	@Autowired
	GuestAccountRepository guests;

	@AfterEach
	void reset() {
		migrator.fail = false;
		migrator.calls.clear();
	}

	private static String email(String prefix) {
		return prefix + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
	}

	private String signupBody(String email, String guestId) {
		return "{\"email\":\"" + email + "\",\"password\":\"secret123\",\"nickname\":\"확장\",\"guestId\":\"" + guestId + "\"}";
	}

	@Test
	void everyRegisteredMigratorIsCalledOnceWithTheGuestAndTheMember() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "확장손님");

		String body = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", guest.bearer()).content(signupBody(email("ext"), guest.ownerId())))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

		String memberId = JsonPath.read(body, "$.ownerId");
		assertThat(migrator.calls).containsExactly(List.of(new Owner(OwnerType.GUEST, guest.ownerId()),
				new Owner(OwnerType.MEMBER, memberId)));
	}

	@Test
	void aFailingMigratorCancelsTheSignupAndLeavesTheGuestIntact() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "실패손님");
		String email = email("fail");
		migrator.fail = true;

		assertThatThrownBy(() -> mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", guest.bearer()).content(signupBody(email, guest.ownerId()))))
				.hasRootCauseInstanceOf(IllegalStateException.class);

		assertThat(users.existsByEmail(email)).as("가입도 취소된다").isFalse();
		assertThat(guests.findById(UUID.fromString(guest.ownerId())).orElseThrow().getMigratedToUserId()).isNull();
		mvc.perform(get("/api/me").header("Authorization", guest.bearer())).andExpect(status().isOk());
		// 게스트의 refresh 토큰도 폐기되지 않았다 (이전 전체가 롤백됨)
		mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + guest.refreshToken() + "\"}")).andExpect(status().isOk());
	}

	@Test
	void aFailingMigratorAlsoCancelsALoginMigration() throws Exception {
		String email = email("loginfail");
		AuthTestClient.signup(mvc, email, "secret123", "기존");
		Tokens guest = AuthTestClient.guest(mvc, "로그인실패손님");
		migrator.fail = true;

		assertThatThrownBy(() -> mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", guest.bearer())
				.content("{\"email\":\"" + email + "\",\"password\":\"secret123\",\"guestId\":\"" + guest.ownerId() + "\"}")))
				.hasRootCauseInstanceOf(IllegalStateException.class);

		assertThat(guests.findById(UUID.fromString(guest.ownerId())).orElseThrow().getMigratedToUserId()).isNull();
	}
}
