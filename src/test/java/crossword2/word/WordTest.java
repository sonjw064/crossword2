package crossword2.word;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WordTest {

	private static Word word(String english) {
		return new Word(english, "뜻", PartOfSpeech.NOUN, "def", Difficulty.EASY, "animals", "ex");
	}

	@Test
	void normalizesCaseAndWhitespace() {
		assertThat(word("  Apple ").getEnglish()).isEqualTo("apple");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "a", "ice cream", "well-known", "abc1", "café", "abcdefghijklmnop" })
	void rejectsInvalidEnglish(String english) {
		assertThatThrownBy(() -> word(english)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsBlankKoreanAndMissingDifficulty() {
		assertThatThrownBy(() -> new Word("cat", " ", null, null, Difficulty.EASY, "animals", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Word("cat", "고양이", null, null, null, "animals", null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void isActiveByDefault() {
		assertThat(word("cat").isActive()).isTrue();
	}
}
