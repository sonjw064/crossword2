package crossword2.feedback;

public enum FeedbackType {
	/** 버그 신고 */
	BUG,
	/** 단어/뜻 오류 신고 (퍼즐 내 빠른 신고 포함) */
	WORD_ERROR,
	/** 기능 제안 */
	SUGGESTION,
	/** 일반 문의 */
	GENERAL
}
