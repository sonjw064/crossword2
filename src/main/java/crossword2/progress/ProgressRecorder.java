package crossword2.progress;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import crossword2.puzzle.PlayFinished;

/**
 * 풀이가 끝날 때 기록과 오답노트를 남긴다. 세션을 끝내는 트랜잭션 안에서 실행되고(실패하면 함께 취소),
 * 같은 사용자의 갱신은 호출 쪽의 계정 행 잠금으로 직렬화되어 있다.
 */
@Component
public class ProgressRecorder {

	private final PlayRecordRepository records;
	private final WrongAnswerRepository wrongAnswers;

	public ProgressRecorder(PlayRecordRepository records, WrongAnswerRepository wrongAnswers) {
		this.records = records;
		this.wrongAnswers = wrongAnswers;
	}

	@EventListener
	@Transactional
	public void on(PlayFinished event) {
		if (records.existsBySessionId(event.sessionId())) {
			return; // 같은 세션은 한 번만 기록한다
		}
		records.save(new PlayRecord(event.sessionId(), event.puzzleId(), event.owner(), event.status(),
				event.elapsedSec(), event.hintCount(), event.wrongCount(), event.endedAt()));

		if (event.troubledWordIds().isEmpty()) {
			return;
		}
		List<WrongAnswer> existing = wrongAnswers.findByOwnerTypeAndOwnerIdAndWordIdIn(event.owner().type(),
				event.owner().id(), event.troubledWordIds());
		Map<Long, WrongAnswer> byWord = new HashMap<>();
		existing.forEach(w -> byWord.put(w.getWordId(), w));
		for (Long wordId : event.troubledWordIds()) {
			WrongAnswer row = byWord.get(wordId);
			if (row == null) {
				wrongAnswers.save(new WrongAnswer(event.owner(), wordId, event.endedAt()));
			} else {
				row.recordAgain(event.endedAt());
			}
		}
	}
}
