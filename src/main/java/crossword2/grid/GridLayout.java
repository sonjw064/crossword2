package crossword2.grid;

import java.util.Arrays;
import java.util.List;

/**
 * 생성된 그리드. {@code rows}는 size개의 문자열이며 빈칸은 {@value #EMPTY}, 나머지는 소문자 글자다.
 * {@code requestedCount}는 입력 단어 수이고, 배치되지 못한 단어는 {@code words}에 없다.
 */
public record GridLayout(int size, List<PlacedWord> words, List<String> rows, int requestedCount, int crossings) {

	public static final char EMPTY = '.';

	public GridLayout {
		words = List.copyOf(words);
		rows = List.copyOf(rows);
	}

	/** 배치 목록에서 칸 배열과 교차 수를 계산해 만든다. 글자가 충돌하면 나중 단어가 덮어쓴다. */
	public static GridLayout fromWords(int size, List<PlacedWord> words, int requestedCount) {
		char[][] cells = new char[size][size];
		for (char[] row : cells) {
			Arrays.fill(row, EMPTY);
		}
		boolean[][] across = new boolean[size][size];
		boolean[][] down = new boolean[size][size];
		for (PlacedWord w : words) {
			boolean[][] used = w.direction() == Direction.ACROSS ? across : down;
			for (int i = 0; i < w.length(); i++) {
				int r = w.rowAt(i);
				int c = w.colAt(i);
				if (r < 0 || c < 0 || r >= size || c >= size) {
					continue;
				}
				cells[r][c] = w.word().charAt(i);
				used[r][c] = true;
			}
		}
		int crossings = 0;
		for (int r = 0; r < size; r++) {
			for (int c = 0; c < size; c++) {
				if (across[r][c] && down[r][c]) {
					crossings++;
				}
			}
		}
		List<String> rows = Arrays.stream(cells).map(String::new).toList();
		return new GridLayout(size, words, rows, requestedCount, crossings);
	}

	/** 빈칸이면 0을 돌려준다. */
	public char letterAt(int row, int col) {
		char ch = rows.get(row).charAt(col);
		return ch == EMPTY ? 0 : ch;
	}

	public double placedRatio() {
		return requestedCount == 0 ? 0 : (double) words.size() / requestedCount;
	}

	@Override
	public String toString() {
		return String.join("\n", rows);
	}
}
