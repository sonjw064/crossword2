package crossword2.puzzle;

import crossword2.auth.Owner;

/**
 * 솔로 풀이 기능(세션 시작, 채점, 힌트, 정답 보기, 단어 카드)을 막아야 하는 상황을 알려주는 확장 지점.
 * 대련 경기에 참가 중인 사용자는 이 기능으로 정답을 알아낼 수 없어야 한다(구현: room 패키지).
 */
public interface PlayRestrictions {

	/** 지금 솔로 풀이 기능을 쓸 수 없으면 403 {@code BATTLE_IN_PROGRESS}를 던진다. 익명(null)은 제한이 없다. */
	void requireSoloPlayAllowed(Owner caller);
}
