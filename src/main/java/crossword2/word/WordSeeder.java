package crossword2.word;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/** 단어 테이블이 비어 있을 때만 {@code seed/words.json}을 적재한다. */
@Component
@Order(1)
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class WordSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(WordSeeder.class);
	private static final String SEED_PATH = "seed/words.json";

	record SeedWord(String english, String korean, PartOfSpeech partOfSpeech, String definition,
			Difficulty difficulty, String topic, String example) {
	}

	private final WordRepository wordRepository;
	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	public WordSeeder(WordRepository wordRepository) {
		this.wordRepository = wordRepository;
	}

	@Override
	public void run(ApplicationArguments args) throws IOException {
		int inserted = seed();
		log.info("Word seed: {} words inserted", inserted);
	}

	/** @return 새로 저장한 단어 수 (이미 데이터가 있으면 0) */
	@Transactional
	public int seed() throws IOException {
		if (wordRepository.count() > 0) {
			return 0;
		}
		try (InputStream in = new ClassPathResource(SEED_PATH).getInputStream()) {
			List<SeedWord> seeds = jsonMapper.readValue(in,
					jsonMapper.getTypeFactory().constructCollectionType(List.class, SeedWord.class));
			List<Word> words = seeds.stream()
					.map(s -> new Word(s.english(), s.korean(), s.partOfSpeech(), s.definition(),
							s.difficulty(), s.topic(), s.example()))
					.toList();
			wordRepository.saveAll(words);
			return words.size();
		}
	}
}
