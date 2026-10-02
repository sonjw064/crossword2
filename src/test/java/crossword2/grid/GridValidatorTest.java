package crossword2.grid;

import static crossword2.grid.Direction.ACROSS;
import static crossword2.grid.Direction.DOWN;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 손으로 만든 고정 사례. 칸 배열(5x5)은 다음과 같다.
 * <pre>
 * c a t . .
 * o . u . .
 * w e b . .
 * </pre>
 */
class GridValidatorTest {

	private static final PlacedWord CAT = new PlacedWord("cat", 0, 0, ACROSS, 1);
	private static final PlacedWord COW = new PlacedWord("cow", 0, 0, DOWN, 1);
	private static final PlacedWord TUB = new PlacedWord("tub", 0, 2, DOWN, 2);
	private static final PlacedWord WEB = new PlacedWord("web", 2, 0, ACROSS, 3);

	private static GridLayout layoutOf(PlacedWord... words) {
		return GridLayout.fromWords(5, List.of(words), words.length);
	}

	@Test
	void numberingSharesNumberAtCommonStartAndFollowsReadingOrder() {
		List<PlacedWord> unnumbered = List.of(
				new PlacedWord("web", 2, 0, ACROSS, 0), new PlacedWord("tub", 0, 2, DOWN, 0),
				new PlacedWord("cow", 0, 0, DOWN, 0), new PlacedWord("cat", 0, 0, ACROSS, 0));

		assertThat(GridNumbering.assign(unnumbered)).containsExactly(CAT, COW, TUB, WEB);
	}

	@Test
	void acceptsValidLayout() {
		GridLayout layout = layoutOf(CAT, COW, TUB, WEB);

		assertThat(layout.crossings()).isEqualTo(4);
		assertThat(GridValidator.validate(layout)).isEmpty();
	}

	@Test
	void detectsWrongNumber() {
		GridLayout layout = layoutOf(CAT, COW, new PlacedWord("tub", 0, 2, DOWN, 5), WEB);

		assertThat(GridValidator.validate(layout)).anyMatch(v -> v.contains("wrong number for tub"));
	}

	@Test
	void detectsLetterMismatch() {
		GridLayout good = layoutOf(CAT, COW, TUB, WEB);
		List<String> rows = new java.util.ArrayList<>(good.rows());
		rows.set(0, "cxt..");
		GridLayout broken = new GridLayout(5, good.words(), rows, 4, good.crossings());

		assertThat(GridValidator.validate(broken)).anyMatch(v -> v.contains("letter mismatch"));
	}

	@Test
	void detectsUnintendedLetterRunFromTouchingWords() {
		GridLayout layout = layoutOf(
				new PlacedWord("cat", 0, 0, ACROSS, 1), new PlacedWord("cow", 0, 0, DOWN, 1),
				new PlacedWord("dog", 1, 1, ACROSS, 2));

		assertThat(GridValidator.validate(layout)).anyMatch(v -> v.contains("unintended letter run"));
	}

	@Test
	void detectsWordThatDoesNotCrossAndDisconnectedGrid() {
		GridLayout layout = layoutOf(
				new PlacedWord("cat", 0, 0, ACROSS, 1), new PlacedWord("dog", 4, 0, ACROSS, 2));

		assertThat(GridValidator.validate(layout))
				.anyMatch(v -> v.contains("does not cross"))
				.anyMatch(v -> v.contains("not connected"));
	}

	@Test
	void detectsWordsJoinedEndToEnd() {
		GridLayout layout = layoutOf(
				new PlacedWord("cat", 0, 0, ACROSS, 1), new PlacedWord("ox", 0, 3, ACROSS, 2));

		assertThat(GridValidator.validate(layout)).anyMatch(v -> v.contains("not a maximal run"));
	}

	@Test
	void detectsOutOfBoundsWord() {
		GridLayout layout = layoutOf(CAT, COW, new PlacedWord("tub", 0, 4, ACROSS, 2));

		assertThat(GridValidator.validate(layout)).anyMatch(v -> v.contains("out of bounds"));
	}

	@Test
	void detectsDuplicateWord() {
		GridLayout layout = layoutOf(CAT, COW, new PlacedWord("cat", 2, 0, ACROSS, 3));

		assertThat(GridValidator.validate(layout)).anyMatch(v -> v.contains("duplicate word"));
	}

	@Test
	void rejectsEmptyLayout() {
		assertThat(GridValidator.validate(layoutOf())).isNotEmpty();
	}
}
