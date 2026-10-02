package crossword2.puzzle;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import crossword2.word.Difficulty;
import crossword2.word.WordRepository;

/** 퍼즐이 하나도 없을 때 개발용 퍼즐을 미리 생성해 둔다. 운영에서는 비활성화하고 관리자 기능으로 만든다. */
@Component
@Order(2)
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class PuzzleSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(PuzzleSeeder.class);
	private static final List<Integer> GENERAL_SIZES = List.of(7, 10);
	private static final int TOPIC_SIZE = 7;
	private static final long BASE_SEED = 1000;

	private final PuzzleGenerationService generationService;
	private final PuzzleRepository puzzleRepository;
	private final WordRepository wordRepository;

	public PuzzleSeeder(PuzzleGenerationService generationService, PuzzleRepository puzzleRepository,
			WordRepository wordRepository) {
		this.generationService = generationService;
		this.puzzleRepository = puzzleRepository;
		this.wordRepository = wordRepository;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (puzzleRepository.count() > 0) {
			return;
		}
		int created = 0;
		long seed = BASE_SEED;
		for (Difficulty difficulty : Difficulty.values()) {
			for (int size : GENERAL_SIZES) {
				created += tryGenerate(difficulty, null, size, seed++);
			}
			for (String topic : wordRepository.findActiveTopics()) {
				created += tryGenerate(difficulty, topic, TOPIC_SIZE, seed++);
			}
		}
		log.info("Puzzle seed: {} puzzles created", created);
	}

	private int tryGenerate(Difficulty difficulty, String topic, int size, long seed) {
		try {
			generationService.generate(difficulty, topic, size, seed, PuzzleType.NORMAL);
			return 1;
		} catch (PuzzleGenerationException e) {
			log.warn("Puzzle seed skipped: {}", e.getMessage());
			return 0;
		}
	}
}
