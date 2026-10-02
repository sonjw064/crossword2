package crossword2.puzzle;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import crossword2.grid.Direction;
import crossword2.word.Difficulty;
import crossword2.word.PartOfSpeech;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** API 요청/응답 모양. 정답 단어는 reveal 응답과 단어 카드 외에는 어디에도 포함하지 않는다. */
public final class PuzzleDtos {

	private PuzzleDtos() {
	}

	public record PuzzleSummary(Long id, int size, Difficulty difficulty, String topic, PuzzleType type,
			int wordCount) {
	}

	public record PuzzlePage(List<PuzzleSummary> items, int page, int pageSize, long totalItems) {
	}

	/** {@code grid}의 각 줄은 size글자: '.'은 글자를 넣는 칸, '#'은 막힌 칸. */
	public record PuzzleView(Long id, int size, Difficulty difficulty, String topic, PuzzleType type,
			List<String> grid, List<EntryView> entries) {
	}

	public record EntryView(Long id, Long wordId, int number, Direction direction, int row, int col, int length, String clue,
			PartOfSpeech partOfSpeech) {
	}

	/** 퍼즐 선택 화면에 보여줄 선택지. 실제로 존재하는 퍼즐에서 뽑는다. */
	public record PuzzleOptions(List<Difficulty> difficulties, List<String> topics, List<Integer> sizes) {
	}

	public record StartResponse(UUID sessionId, Long puzzleId, Instant startedAt) {
	}

	public record AnswerItem(@NotNull Long entryId, @NotNull @Size(max = 30) String answer) {
	}

	public record CheckRequest(@NotEmpty @Size(max = 100) List<@Valid @NotNull AnswerItem> answers) {
	}

	public enum AnswerStatus {
		CORRECT, WRONG, INCOMPLETE
	}

	public record EntryResult(Long entryId, AnswerStatus status) {
	}

	/** {@code elapsedSec}는 풀이를 마쳤을 때만 채워진다. */
	public record CheckResponse(List<EntryResult> results, boolean completed, int wrongCount, int hintCount,
			Long elapsedSec) {
	}

	public record EntryRequest(@NotNull Long entryId) {
	}

	public record HintResponse(Long entryId, String letter, int hintCount) {
	}

	public record DefinitionHintResponse(Long entryId, String definition, int hintCount) {
	}

	public record WordCard(Long wordId, String english, String korean, PartOfSpeech partOfSpeech, String definition,
			String example) {
	}

	public record RevealedEntry(Long entryId, WordCard card) {
	}

	public record RevealResponse(List<RevealedEntry> entries, int wrongCount, int hintCount, long elapsedSec) {
	}
}
