package crossword2.puzzle;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import crossword2.auth.Owner;

/**
 * 소유자가 있는 풀이 세션이 끝났을 때(완료 또는 정답 보기) 발행하는 이벤트. 세션을 끝내는 트랜잭션 안에서 동기로 처리되므로
 * 리스너가 실패하면 세션 종료도 함께 취소된다. 익명 세션은 발행하지 않는다.
 *
 * @param troubledWordIds 오답노트에 올릴 단어: 틀린 적 있는 단어, 힌트를 쓴 단어, (포기한 경우) 끝까지 못 맞힌 단어
 */
public record PlayFinished(UUID sessionId, Owner owner, Long puzzleId, PlayStatus status, long elapsedSec,
		int hintCount, int wrongCount, Instant endedAt, Set<Long> troubledWordIds) {
}
