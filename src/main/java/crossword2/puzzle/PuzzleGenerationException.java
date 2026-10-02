package crossword2.puzzle;

public class PuzzleGenerationException extends RuntimeException {

	private final boolean timeout;

	public PuzzleGenerationException(String message) {
		this(message, false);
	}

	private PuzzleGenerationException(String message, boolean timeout) {
		super(message);
		this.timeout = timeout;
	}

	/** 환경 부하로 시간 제한을 넘긴 경우. 다른 seed로 대체하지 않고 실패로 알린다. */
	public static PuzzleGenerationException timedOut(String message) {
		return new PuzzleGenerationException(message, true);
	}

	public boolean isTimeout() {
		return timeout;
	}
}
