package crossword2.feedback;

/** 퍼즐 내 빠른 신고 사유. */
public enum FeedbackReason {
	/** 뜻이 틀림 */
	WRONG_MEANING,
	/** 정답이 여러 개 */
	MULTIPLE_ANSWERS,
	/** 오타 */
	TYPO,
	/** 기타 */
	OTHER
}
