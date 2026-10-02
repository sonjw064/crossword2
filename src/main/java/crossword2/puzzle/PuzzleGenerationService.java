package crossword2.puzzle;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crossword2.grid.GridGenerationException;
import crossword2.grid.GridGenerator;
import crossword2.grid.GridLayout;
import crossword2.grid.PlacedWord;
import crossword2.word.Difficulty;
import crossword2.word.Word;
import crossword2.word.WordRepository;

/**
 * 단어 풀에서 퍼즐을 만들어 DB에 저장한다. 요청 시 즉석 생성하지 않고 시드/관리자 작업에서만 호출한다.
 * 풀 규칙: 난이도는 "이하"(EASY=쉬움, MEDIUM=쉬움+보통, HARD=전체), 주제는 null이면 전체.
 */
@Service
public class PuzzleGenerationService {

	static final int MAX_ATTEMPTS = 10;
	private static final long ATTEMPT_SEED_STEP = 7919L;

	private final WordRepository wordRepository;
	private final PuzzleRepository puzzleRepository;
	private final Clock clock;
	private final GridGenerator generator = new GridGenerator();

	public PuzzleGenerationService(WordRepository wordRepository, PuzzleRepository puzzleRepository, Clock clock) {
		this.wordRepository = wordRepository;
		this.puzzleRepository = puzzleRepository;
		this.clock = clock;
	}

	/** 같은 (난이도, 주제, 크기, seed)와 같은 단어 데이터라면 항상 같은 퍼즐이 나온다. */
	@Transactional
	public Puzzle generate(Difficulty difficulty, String topic, int size, long seed, PuzzleType type) {
		if (size < GridGenerator.MIN_SIZE || size > GridGenerator.MAX_SIZE) {
			throw new PuzzleGenerationException("unsupported size: " + size);
		}
		List<Word> pool = loadPool(difficulty, topic).stream()
				.filter(w -> w.getEnglish().length() <= size)
				.toList();
		int poolSize = (int) Math.round(size * 1.5);
		int minWords = (int) Math.round(size * 0.7);
		if (pool.size() < minWords) {
			throw new PuzzleGenerationException("not enough words (" + pool.size() + ") for size " + size);
		}

		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			long attemptSeed = seed + attempt * ATTEMPT_SEED_STEP;
			List<Word> picked = new ArrayList<>(pool);
			Collections.shuffle(picked, new Random(attemptSeed));
			picked = picked.subList(0, Math.min(poolSize, picked.size()));

			Map<String, Word> byEnglish = new HashMap<>();
			picked.forEach(w -> byEnglish.put(w.getEnglish(), w));
			GridLayout layout;
			try {
				layout = generator.generate(new ArrayList<>(byEnglish.keySet()).stream().sorted().toList(), size,
						attemptSeed);
			} catch (GridGenerationException e) {
				continue;
			}
			if (layout.words().size() >= minWords) {
				return puzzleRepository.save(build(layout, byEnglish, difficulty, topic, size, seed, type));
			}
		}
		throw new PuzzleGenerationException("could not generate a puzzle (difficulty=" + difficulty + ", topic="
				+ topic + ", size=" + size + ", seed=" + seed + ")");
	}

	private List<Word> loadPool(Difficulty difficulty, String topic) {
		EnumSet<Difficulty> levels = EnumSet.range(Difficulty.EASY, difficulty);
		return topic == null
				? wordRepository.findByActiveTrueAndDifficultyInOrderById(levels)
				: wordRepository.findByActiveTrueAndDifficultyInAndTopicOrderById(levels, topic);
	}

	private Puzzle build(GridLayout layout, Map<String, Word> byEnglish, Difficulty difficulty, String topic,
			int size, long seed, PuzzleType type) {
		Puzzle puzzle = new Puzzle(size, seed, difficulty, topic, type, layout.words().size(), clock.instant());
		for (PlacedWord w : layout.words()) {
			puzzle.addEntry(byEnglish.get(w.word()), w.row(), w.col(), w.direction(), w.number());
		}
		return puzzle;
	}
}
