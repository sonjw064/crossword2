package crossword2.grid;

public enum Direction {
	ACROSS(0, 1),
	DOWN(1, 0);

	private final int rowStep;
	private final int colStep;

	Direction(int rowStep, int colStep) {
		this.rowStep = rowStep;
		this.colStep = colStep;
	}

	public int rowStep() {
		return rowStep;
	}

	public int colStep() {
		return colStep;
	}
}
