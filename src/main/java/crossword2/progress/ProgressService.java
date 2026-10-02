package crossword2.progress;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crossword2.auth.Owner;
import crossword2.progress.ProgressDtos.ProgressResponse;
import crossword2.progress.ProgressDtos.ProgressSummary;
import crossword2.progress.ProgressDtos.RecordView;
import crossword2.progress.ProgressDtos.WrongAnswerItem;
import crossword2.progress.ProgressDtos.WrongAnswersResponse;
import crossword2.puzzle.PlayStatus;
import crossword2.puzzle.Puzzle;
import crossword2.puzzle.PuzzleRepository;
import crossword2.word.Word;
import crossword2.word.WordRepository;

/** 본인의 풀이 기록과 오답노트 조회. 항상 호출한 사용자(토큰의 주체) 자신의 데이터만 돌려준다. */
@Service
@Transactional(readOnly = true)
public class ProgressService {

	public static final int MAX_PAGE_SIZE = 50;

	public enum WrongAnswerSort {
		RECENT, COUNT
	}

	private final PlayRecordRepository records;
	private final WrongAnswerRepository wrongAnswers;
	private final PuzzleRepository puzzles;
	private final WordRepository words;

	public ProgressService(PlayRecordRepository records, WrongAnswerRepository wrongAnswers,
			PuzzleRepository puzzles, WordRepository words) {
		this.records = records;
		this.wrongAnswers = wrongAnswers;
		this.puzzles = puzzles;
		this.words = words;
	}

	public ProgressResponse progress(Owner owner, int page, int pageSize) {
		int size = clampSize(pageSize);
		Double average = records.averageElapsed(owner.type(), owner.id(), PlayStatus.COMPLETED);
		ProgressSummary summary = new ProgressSummary(
				records.countByOwnerTypeAndOwnerIdAndStatus(owner.type(), owner.id(), PlayStatus.COMPLETED),
				records.countByOwnerTypeAndOwnerIdAndStatus(owner.type(), owner.id(), PlayStatus.GAVE_UP),
				average == null ? null : Math.round(average),
				records.bestElapsed(owner.type(), owner.id(), PlayStatus.COMPLETED),
				records.distinctPuzzleIds(owner.type(), owner.id(), PlayStatus.COMPLETED));

		Page<PlayRecord> result = records.findByOwnerTypeAndOwnerId(owner.type(), owner.id(),
				PageRequest.of(Math.max(page, 0), size, Sort.by(Sort.Order.desc("completedAt"), Sort.Order.desc("id"))));
		Map<Long, Puzzle> puzzleById = puzzles.findAllById(result.getContent().stream().map(PlayRecord::getPuzzleId).distinct().toList())
				.stream().collect(Collectors.toMap(Puzzle::getId, Function.identity()));
		List<RecordView> views = result.getContent().stream().map(r -> {
			Puzzle p = puzzleById.get(r.getPuzzleId());
			return new RecordView(r.getId(), r.getPuzzleId(), p == null ? null : p.getDifficulty(),
					p == null ? null : p.getTopic(), p == null ? null : p.getSize(), r.getStatus(), r.getElapsedSec(),
					r.getHintCount(), r.getWrongCount(), r.getCompletedAt());
		}).toList();
		return new ProgressResponse(summary, views, result.getNumber(), result.getSize(), result.getTotalElements());
	}

	public WrongAnswersResponse wrongAnswers(Owner owner, int page, int pageSize, WrongAnswerSort sort) {
		Sort order = sort == WrongAnswerSort.COUNT
				? Sort.by(Sort.Order.desc("count"), Sort.Order.desc("lastAt"), Sort.Order.asc("id"))
				: Sort.by(Sort.Order.desc("lastAt"), Sort.Order.desc("count"), Sort.Order.asc("id"));
		Page<WrongAnswer> result = wrongAnswers.findByOwnerTypeAndOwnerId(owner.type(), owner.id(),
				PageRequest.of(Math.max(page, 0), clampSize(pageSize), order));
		Map<Long, Word> wordById = words.findAllById(result.getContent().stream().map(WrongAnswer::getWordId).toList())
				.stream().collect(Collectors.toMap(Word::getId, Function.identity()));
		List<WrongAnswerItem> items = result.getContent().stream()
				.filter(w -> wordById.containsKey(w.getWordId()))
				.map(w -> {
					Word word = wordById.get(w.getWordId());
					return new WrongAnswerItem(word.getId(), word.getEnglish(), word.getKorean(), word.getPartOfSpeech(),
							word.getDefinition(), word.getExample(), w.getCount(), w.getLastAt());
				}).toList();
		return new WrongAnswersResponse(items, result.getNumber(), result.getSize(), result.getTotalElements());
	}

	private static int clampSize(int pageSize) {
		return Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
	}
}
