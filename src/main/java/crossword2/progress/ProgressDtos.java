package crossword2.progress;

import java.time.Instant;
import java.util.List;

import crossword2.puzzle.PlayStatus;
import crossword2.word.Difficulty;
import crossword2.word.PartOfSpeech;

public final class ProgressDtos {

	private ProgressDtos() {
	}

	/** 평균/최고 시간은 완료한 풀이가 없으면 null. {@code completedPuzzleIds}는 완료한 퍼즐(중복 없음). */
	public record ProgressSummary(long completed, long gaveUp, Long averageSeconds, Long bestSeconds,
			List<Long> completedPuzzleIds) {
	}

	public record RecordView(Long id, Long puzzleId, Difficulty difficulty, String topic, Integer size,
			PlayStatus status, long elapsedSec, int hintCount, int wrongCount, Instant completedAt) {
	}

	public record ProgressResponse(ProgressSummary summary, List<RecordView> records, int page, int pageSize,
			long totalRecords) {
	}

	/** 오답노트의 단어 카드. 종료된 풀이에서 기록된 단어만 오므로(이미 공개된 단어) 정답이 포함되어도 정책에 맞다. */
	public record WrongAnswerItem(Long wordId, String english, String korean, PartOfSpeech partOfSpeech,
			String definition, String example, int count, Instant lastAt) {
	}

	public record WrongAnswersResponse(List<WrongAnswerItem> items, int page, int pageSize, long totalItems) {
	}
}
