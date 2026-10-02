package crossword2.word;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest(properties = "app.seed.enabled=false")
class WordRepositoryTest {

	@Autowired
	WordRepository wordRepository;

	private Word word(String english, Difficulty difficulty, String topic) {
		return new Word(english, "뜻", PartOfSpeech.NOUN, "def", difficulty, topic, "ex");
	}

	@Test
	void savesAndFindsByEnglish() {
		Word saved = wordRepository.saveAndFlush(word("cat", Difficulty.EASY, "animals"));

		assertThat(saved.getId()).isNotNull();
		assertThat(wordRepository.existsByEnglish("cat")).isTrue();
		assertThat(wordRepository.existsByEnglish("dog")).isFalse();
	}

	@Test
	void rejectsDuplicateEnglish() {
		wordRepository.saveAndFlush(word("cat", Difficulty.EASY, "animals"));

		assertThatThrownBy(() -> wordRepository.saveAndFlush(word("Cat", Difficulty.HARD, "home")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void filtersByDifficultyAndTopic() {
		wordRepository.save(word("cat", Difficulty.EASY, "animals"));
		wordRepository.save(word("dog", Difficulty.EASY, "animals"));
		wordRepository.save(word("tiger", Difficulty.MEDIUM, "animals"));
		wordRepository.save(word("apple", Difficulty.EASY, "food"));

		assertThat(wordRepository.findByActiveTrueAndDifficultyAndTopic(Difficulty.EASY, "animals"))
				.extracting(Word::getEnglish)
				.containsExactlyInAnyOrder("cat", "dog");
	}
}
