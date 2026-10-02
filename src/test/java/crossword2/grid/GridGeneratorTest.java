package crossword2.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import crossword2.grid.GridGenerationException.Reason;

class GridGeneratorTest {

	private final GridGenerator generator = new GridGenerator();

	@Test
	void sameInputAndSeedGiveSameResult() {
		List<String> words = TestWords.pick(15, 8, 1);

		for (long seed = 1; seed <= 20; seed++) {
			assertThat(generator.generate(words, 10, seed)).isEqualTo(generator.generate(words, 10, seed));
		}
	}

	@Test
	void differentSeedsGiveDifferentResults() {
		List<String> words = TestWords.pick(15, 8, 1);

		Set<GridLayout> layouts = new HashSet<>();
		for (long seed = 1; seed <= 10; seed++) {
			layouts.add(generator.generate(words, 10, seed));
		}

		assertThat(layouts.size()).isGreaterThan(5);
	}

	@ParameterizedTest
	@CsvSource({ "7, 8, 6", "10, 15, 8", "12, 22, 8", "15, 30, 10" })
	void generatedGridsSatisfyAllRules(int size, int wordCount, int maxWordLength) {
		for (long seed = 1; seed <= 40; seed++) {
			List<String> words = TestWords.pick(wordCount, maxWordLength, seed);

			GridLayout layout = generator.generate(words, size, seed);

			assertThat(GridValidator.validate(layout))
					.as("size=%d seed=%d%n%s", size, seed, layout)
					.isEmpty();
			assertThat(layout.placedRatio()).isGreaterThanOrEqualTo(0.5);
			assertThat(layout.crossings()).isGreaterThanOrEqualTo(layout.words().size() - 1);
			assertThat(layout.words()).extracting(PlacedWord::word).isSubsetOf(words);
		}
	}

	@Test
	void numbersFollowReadingOrder() {
		GridLayout layout = generator.generate(TestWords.pick(15, 8, 3), 10, 3);

		int previous = 0;
		for (PlacedWord w : layout.words()) {
			assertThat(w.number()).isGreaterThanOrEqualTo(previous);
			previous = w.number();
		}
		assertThat(layout.words().get(0).number()).isEqualTo(1);
	}

	@Test
	void generatesMaximumSizeWithinTimeLimit() {
		for (long s = 1; s <= 5; s++) {
			long seed = s;
			List<String> words = TestWords.pick(30, 10, seed);

			GridLayout layout = assertTimeoutPreemptively(Duration.ofSeconds(2),
					() -> generator.generate(words, 15, seed));

			assertThat(GridValidator.isValid(layout)).isTrue();
		}
	}

	@Test
	void failsWhenWordsAreTooFew() {
		assertFailure(List.of("cat", "dog"), 10, Reason.TOO_FEW_WORDS);
	}

	@Test
	void failsWhenWordIsLongerThanGrid() {
		assertFailure(List.of("cat", "dog", "elephant"), 7, Reason.WORD_TOO_LONG);
	}

	@ParameterizedTest
	@CsvSource({ "Cat", "c4t", "'c t'", "a", "''" })
	void failsOnInvalidWord(String bad) {
		assertFailure(List.of("dog", "bird", bad), 10, Reason.INVALID_WORD);
	}

	@Test
	void failsOnDuplicateWord() {
		assertFailure(List.of("cat", "dog", "cat"), 10, Reason.DUPLICATE_WORD);
	}

	@ParameterizedTest
	@CsvSource({ "6", "16" })
	void failsOnUnsupportedSize(int size) {
		assertFailure(List.of("cat", "dog", "bird"), size, Reason.INVALID_SIZE);
	}

	@Test
	void failsWhenWordsCannotCross() {
		assertFailure(List.of("abc", "def", "ghi", "jkl"), 10, Reason.QUALITY_NOT_MET);
	}

	@Test
	void failsWithTimeoutWhenTimeLimitIsZero() {
		GridGenerator strict = new GridGenerator(GeneratorConfig.defaults().withTimeLimit(Duration.ZERO));

		assertThatThrownBy(() -> strict.generate(TestWords.pick(10, 10, 1), 10, 1))
				.isInstanceOfSatisfying(GridGenerationException.class, e -> assertThat(e.reason()).isEqualTo(Reason.TIMEOUT));
	}

	private void assertFailure(List<String> words, int size, Reason reason) {
		assertThatThrownBy(() -> generator.generate(words, size, 1))
				.isInstanceOfSatisfying(GridGenerationException.class, e -> assertThat(e.reason()).isEqualTo(reason));
	}
}
