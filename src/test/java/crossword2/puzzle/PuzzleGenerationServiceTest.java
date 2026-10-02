package crossword2.puzzle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import crossword2.grid.GeneratorConfig;
import crossword2.grid.GridGenerator;
import crossword2.grid.GridLayout;
import crossword2.grid.GridValidator;
import crossword2.grid.PlacedWord;
import crossword2.word.Difficulty;
import crossword2.word.WordRepository;

@SpringBootTest
@Transactional
class PuzzleGenerationServiceTest {

	@Autowired
	PuzzleGenerationService service;

	@Autowired
	PuzzleRepository puzzleRepository;

	@Autowired
	WordRepository wordRepository;

	@Autowired
	Clock clock;

	private static List<String> signature(Puzzle p) {
		return p.getEntries().stream()
				.map(e -> e.getWord().getEnglish() + "@" + e.getStartRow() + "," + e.getStartCol() + ","
						+ e.getDirection() + "#" + e.getNumber())
				.toList();
	}

	@Test
	void sameSeedGivesSamePuzzle() {
		Puzzle a = service.generate(Difficulty.MEDIUM, null, 10, 42, PuzzleType.NORMAL);
		Puzzle b = service.generate(Difficulty.MEDIUM, null, 10, 42, PuzzleType.NORMAL);

		assertThat(signature(a)).isEqualTo(signature(b));
	}

	@Test
	void savedPuzzleIsAValidGrid() {
		Puzzle puzzle = service.generate(Difficulty.HARD, null, 12, 7, PuzzleType.NORMAL);

		List<PlacedWord> placed = puzzle.getEntries().stream()
				.map(e -> new PlacedWord(e.getWord().getEnglish(), e.getStartRow(), e.getStartCol(),
						e.getDirection(), e.getNumber()))
				.toList();
		GridLayout layout = GridLayout.fromWords(12, placed, placed.size());

		assertThat(GridValidator.validate(layout)).isEmpty();
		assertThat(puzzle.getWordCount()).isEqualTo(puzzle.getEntries().size()).isGreaterThanOrEqualTo(8);
		assertThat(puzzleRepository.findById(puzzle.getId())).isPresent();
	}

	@Test
	void easyPuzzleUsesOnlyEasyWords() {
		Puzzle puzzle = service.generate(Difficulty.EASY, null, 7, 3, PuzzleType.NORMAL);

		assertThat(puzzle.getEntries()).allSatisfy(e -> assertThat(e.getWord().getDifficulty()).isEqualTo(Difficulty.EASY));
	}

	@Test
	void mediumPuzzleNeverUsesHardWords() {
		Puzzle puzzle = service.generate(Difficulty.MEDIUM, null, 10, 5, PuzzleType.NORMAL);

		assertThat(puzzle.getEntries()).allSatisfy(e -> assertThat(e.getWord().getDifficulty()).isNotEqualTo(Difficulty.HARD));
	}

	@Test
	void topicPuzzleUsesOnlyThatTopic() {
		Puzzle puzzle = service.generate(Difficulty.HARD, "animals", 7, 11, PuzzleType.NORMAL);

		assertThat(puzzle.getTopic()).isEqualTo("animals");
		assertThat(puzzle.getEntries()).allSatisfy(e -> assertThat(e.getWord().getTopic()).isEqualTo("animals"));
	}

	@Test
	void failsWhenPoolIsTooSmall() {
		assertThatThrownBy(() -> service.generate(Difficulty.EASY, "no-such-topic", 7, 1, PuzzleType.NORMAL))
				.isInstanceOf(PuzzleGenerationException.class);
	}

	@Test
	void failsOnUnsupportedSize() {
		assertThatThrownBy(() -> service.generate(Difficulty.EASY, null, 5, 1, PuzzleType.NORMAL))
				.isInstanceOf(PuzzleGenerationException.class);
	}

	@Test
	void timeoutFailsInsteadOfFallingBackToAnotherSeed() {
		GridGenerator timedOut = new GridGenerator(GeneratorConfig.defaults().withTimeLimit(Duration.ZERO));
		PuzzleGenerationService slow = new PuzzleGenerationService(wordRepository, puzzleRepository, clock, timedOut);
		long before = puzzleRepository.count();

		assertThatThrownBy(() -> slow.generate(Difficulty.MEDIUM, null, 10, 42, PuzzleType.NORMAL))
				.isInstanceOfSatisfying(PuzzleGenerationException.class, e -> assertThat(e.isTimeout()).isTrue());

		assertThat(puzzleRepository.count()).isEqualTo(before);
	}

	@Test
	void resultDoesNotDependOnTheTimeLimit() {
		GridGenerator generous = new GridGenerator(GeneratorConfig.defaults().withTimeLimit(Duration.ofMinutes(5)));
		PuzzleGenerationService relaxed = new PuzzleGenerationService(wordRepository, puzzleRepository, clock, generous);

		Puzzle normal = service.generate(Difficulty.MEDIUM, null, 10, 42, PuzzleType.NORMAL);
		Puzzle relaxedResult = relaxed.generate(Difficulty.MEDIUM, null, 10, 42, PuzzleType.NORMAL);

		assertThat(signature(relaxedResult)).isEqualTo(signature(normal));
	}

	@Test
	void everyPuzzleMeetsTheCrossingTarget() {
		double ratio = GeneratorConfig.defaults().minCrossingRatio();
		for (Puzzle p : puzzleRepository.findAll()) {
			List<PlacedWord> placed = p.getEntries().stream()
					.map(e -> new PlacedWord(e.getWord().getEnglish(), e.getStartRow(), e.getStartCol(),
							e.getDirection(), e.getNumber()))
					.toList();
			GridLayout layout = GridLayout.fromWords(p.getSize(), placed, placed.size());

			assertThat(layout.crossings()).as("puzzle %d", p.getId())
					.isGreaterThanOrEqualTo((int) Math.floor(ratio * placed.size()))
					.isGreaterThanOrEqualTo(placed.size() - 1);
		}
	}

	@Test
	void seederCreatedPuzzlesForEveryDifficulty() {
		for (Difficulty d : Difficulty.values()) {
			long count = puzzleRepository.findAll().stream().filter(p -> p.getDifficulty() == d).count();
			assertThat(count).as("seeded puzzles for %s", d).isGreaterThanOrEqualTo(3);
		}
	}
}
