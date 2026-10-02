package crossword2.grid;

public class GridGenerationException extends RuntimeException {

	public enum Reason {
		INVALID_SIZE,
		TOO_FEW_WORDS,
		INVALID_WORD,
		DUPLICATE_WORD,
		WORD_TOO_LONG,
		QUALITY_NOT_MET,
		TIMEOUT
	}

	private final Reason reason;

	public GridGenerationException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
