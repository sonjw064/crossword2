package crossword2.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.UUID;

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

import crossword2.auth.AuthTestClient;
import crossword2.auth.AuthTestClient.Tokens;
import crossword2.auth.GuestAccountRepository;
import crossword2.auth.GuestDataMigrator;
import crossword2.auth.Owner;
import crossword2.auth.OwnerType;
import crossword2.auth.UserRepository;
import crossword2.word.WordRepository;

/** 다른 데이터의 이전이 실패하면 진도/오답노트 병합도 함께 취소된다(가입 포함). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProgressMigrationRollbackTest.Config.class)
class ProgressMigrationRollbackTest {

	static class SwitchableFailure implements GuestDataMigrator {

		volatile boolean fail;

		@Override
		public void migrate(Owner guest, Owner member) {
			if (fail) {
				throw new IllegalStateException("boom");
			}
		}
	}

	@TestConfiguration
	static class Config {

		@Bean
		SwitchableFailure switchableFailure() {
			return new SwitchableFailure();
		}
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	SwitchableFailure failure;

	@Autowired
	WrongAnswerRepository wrongAnswers;

	@Autowired
	PlayRecordRepository records;

	@Autowired
	WordRepository words;

	@Autowired
	UserRepository users;

	@Autowired
	GuestAccountRepository guests;

	@AfterEach
	void reset() {
		failure.fail = false;
	}

	@Test
	void aFailureElsewhereLeavesTheGuestsProgressUntouched() throws Exception {
		Tokens guest = AuthTestClient.guest(mvc, "롤백손님");
		Owner g = new Owner(OwnerType.GUEST, guest.ownerId());
		Long wordId = words.findAll().get(0).getId();
		wrongAnswers.save(new WrongAnswer(g, wordId, Instant.parse("2026-03-01T00:00:00Z")));
		String email = "rb" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
		failure.fail = true;

		assertThatThrownBy(() -> mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", guest.bearer())
				.content("{\"email\":\"" + email + "\",\"password\":\"secret123\",\"nickname\":\"롤백\",\"guestId\":\""
						+ guest.ownerId() + "\"}"))).hasRootCauseInstanceOf(IllegalStateException.class);

		assertThat(users.existsByEmail(email)).isFalse();
		assertThat(wrongAnswers.findByOwnerTypeAndOwnerId(g.type(), g.id())).hasSize(1);
		assertThat(guests.findById(UUID.fromString(guest.ownerId())).orElseThrow().getMigratedToUserId()).isNull();
	}
}
