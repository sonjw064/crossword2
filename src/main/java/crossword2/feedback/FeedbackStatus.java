package crossword2.feedback;

/** 접수 → 확인 중 → 처리 완료/반려. 상태 변경은 4단계 관리자 기능에서 한다. */
public enum FeedbackStatus {
	RECEIVED, IN_REVIEW, RESOLVED, REJECTED
}
