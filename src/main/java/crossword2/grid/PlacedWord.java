package crossword2.grid;

/** 그리드에 배치된 단어. {@code number}가 0이면 아직 번호가 부여되지 않은 상태다. */
public record PlacedWord(String word, int row, int col, Direction direction, int number) {

	public int length() {
		return word.length();
	}

	public int rowAt(int index) {
		return row + direction.rowStep() * index;
	}

	public int colAt(int index) {
		return col + direction.colStep() * index;
	}

	PlacedWord withNumber(int newNumber) {
		return new PlacedWord(word, row, col, direction, newNumber);
	}
}
