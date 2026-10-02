package crossword2.word;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class WordSeederTest {

	@Autowired
	WordRepository wordRepository;

	@Autowired
	WordSeeder wordSeeder;

	@Test
	void seedsOnStartup() {
		assertThat(wordRepository.count()).isGreaterThanOrEqualTo(100);
	}

	@Test
	void seedIsIdempotent() throws IOException {
		long before = wordRepository.count();

		assertThat(wordSeeder.seed()).isZero();
		assertThat(wordRepository.count()).isEqualTo(before);
	}

	@Test
	void seededWordsAreValidAndComplete() {
		List<Word> words = wordRepository.findAll();

		assertThat(words).extracting(Word::getEnglish).doesNotHaveDuplicates()
				.allMatch(w -> w.matches("[a-z]{2,15}"));
		assertThat(words).allSatisfy(w -> {
			assertThat(w.getKorean()).isNotBlank();
			assertThat(w.getDefinition()).isNotBlank();
			assertThat(w.getPartOfSpeech()).isNotNull();
			assertThat(w.getTopic()).isNotBlank();
			assertThat(w.isActive()).isTrue();
		});
		for (Difficulty d : Difficulty.values()) {
			assertThat(words).anyMatch(w -> w.getDifficulty() == d);
		}
	}
}
