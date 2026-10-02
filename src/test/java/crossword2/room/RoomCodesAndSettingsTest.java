package crossword2.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import crossword2.common.ApiException;
import crossword2.word.Difficulty;

class RoomCodesAndSettingsTest {

	private static final Set<RoomMode> RACE_ONLY = EnumSet.of(RoomMode.RACE);

	// ---- 방 코드 ----

	@Test
	void codesAreSixCharactersFromTheUnambiguousAlphabet() {
		for (int i = 0; i < 2000; i++) {
			String code = RoomCodes.generate();

			assertThat(code).hasSize(6).matches("[" + RoomCodes.ALPHABET + "]{6}");
			assertThat(code).doesNotContain("0", "O", "1", "I");
		}
	}

	@Test
	void theAlphabetHasThirtyTwoDistinctCharactersWithoutConfusingOnes() {
		assertThat(RoomCodes.ALPHABET).hasSize(32).doesNotContain("0", "O", "1", "I");
		assertThat(new HashSet<>(RoomCodes.ALPHABET.chars().boxed().toList())).hasSize(32);
	}

	@Test
	void generatedCodesAreSpreadOutAndRarelyCollide() {
		Set<String> codes = new HashSet<>();
		for (int i = 0; i < 20_000; i++) {
			codes.add(RoomCodes.generate());
		}

		assertThat(codes.size()).as("20,000개 중 충돌은 아주 드물다").isGreaterThan(19_990);
	}

	@Test
	void allCharactersOfTheAlphabetAreUsed() {
		Random random = new Random(1);
		Set<Character> seen = new HashSet<>();
		for (int i = 0; i < 2000; i++) {
			RoomCodes.generate(random).chars().forEach(c -> seen.add((char) c));
		}

		assertThat(seen).hasSize(32);
	}

	@ParameterizedTest
	@ValueSource(strings = { "abc234", "  ABC234 ", "Abc234\n" })
	void normalizeAcceptsCaseAndSurroundingWhitespace(String raw) {
		assertThat(RoomCodes.normalize(raw)).isEqualTo("ABC234");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   ", "ABC23", "ABC2345", "ABC23I", "ABC23O", "ABC231", "ABC230", "ABC-23", "ABC 23", "한글코드여섯" })
	void normalizeRejectsMalformedCodes(String raw) {
		assertThat(RoomCodes.normalize(raw)).isNull();
	}

	@Test
	void normalizeRejectsNull() {
		assertThat(RoomCodes.normalize(null)).isNull();
	}

	// ---- 설정 ----

	private static RoomSettings validated(int size, int time, int players) {
		return RoomSettings.validated(Difficulty.EASY, "animals", size, time, players, RoomMode.RACE, RACE_ONLY);
	}

	@Test
	void validSettingsAreNormalized() {
		RoomSettings s = RoomSettings.validated(Difficulty.MEDIUM, "  food  ", 10, 600, 4, RoomMode.RACE, RACE_ONLY);

		assertThat(s.topic()).isEqualTo("food");
		assertThat(RoomSettings.validated(Difficulty.EASY, "   ", 7, 60, 2, RoomMode.RACE, RACE_ONLY).topic()).isNull();
		assertThat(RoomSettings.validated(Difficulty.EASY, null, 15, 3600, 8, RoomMode.RACE, RACE_ONLY).maxPlayers()).isEqualTo(8);
	}

	@ParameterizedTest
	@CsvSource({ "6, 600, 4", "16, 600, 4", "10, 59, 4", "10, 3601, 4", "10, 600, 1", "10, 600, 9", "10, 600, 0", "10, -5, 4" })
	void outOfRangeValuesAreRejected(int size, int time, int players) {
		assertThatThrownBy(() -> validated(size, time, players)).isInstanceOfSatisfying(ApiException.class, e -> {
			assertThat(e.status().value()).isEqualTo(400);
			assertThat(e.code()).isEqualTo("SETTINGS_INVALID");
		});
	}

	@Test
	void missingDifficultyOrModeAndTooLongTopicsAreRejected() {
		assertThatThrownBy(() -> RoomSettings.validated(null, null, 10, 600, 4, RoomMode.RACE, RACE_ONLY))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> RoomSettings.validated(Difficulty.EASY, null, 10, 600, 4, null, RACE_ONLY))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> RoomSettings.validated(Difficulty.EASY, "가".repeat(51), 10, 600, 4, RoomMode.RACE, RACE_ONLY))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void modesThatAreNotEnabledYetAreRejectedWithAClearCode() {
		for (RoomMode mode : new RoomMode[] { RoomMode.WORD_CLAIM, RoomMode.COOP }) {
			assertThatThrownBy(() -> RoomSettings.validated(Difficulty.EASY, null, 10, 600, 4, mode, RACE_ONLY))
					.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("MODE_NOT_AVAILABLE"));
		}
		assertThat(RoomSettings.validated(Difficulty.EASY, null, 10, 600, 4, RoomMode.COOP, EnumSet.allOf(RoomMode.class)).mode())
				.isEqualTo(RoomMode.COOP);
	}
}
