package crossword2.puzzle;

import java.util.Arrays;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crossword2.common.ApiException;
import crossword2.puzzle.PuzzleDtos.EntryView;
import crossword2.puzzle.PuzzleDtos.PuzzlePage;
import crossword2.puzzle.PuzzleDtos.PuzzleSummary;
import crossword2.puzzle.PuzzleDtos.PuzzleView;
import crossword2.word.Difficulty;

@Service
@Transactional(readOnly = true)
public class PuzzleService {

	static final int MAX_PAGE_SIZE = 50;

	private final PuzzleRepository puzzleRepository;

	public PuzzleService(PuzzleRepository puzzleRepository) {
		this.puzzleRepository = puzzleRepository;
	}

	public PuzzlePage list(Difficulty difficulty, String topic, Integer size, int page, int pageSize) {
		Specification<Puzzle> spec = (root, q, cb) -> cb.conjunction();
		if (difficulty != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("difficulty"), difficulty));
		}
		if (topic != null && !topic.isBlank()) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("topic"), topic));
		}
		if (size != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("size"), size));
		}
		PageRequest request = PageRequest.of(Math.max(page, 0), Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE),
				Sort.by("id"));
		Page<Puzzle> result = puzzleRepository.findAll(spec, request);
		List<PuzzleSummary> items = result.getContent().stream()
				.map(p -> new PuzzleSummary(p.getId(), p.getSize(), p.getDifficulty(), p.getTopic(), p.getType(),
						p.getWordCount()))
				.toList();
		return new PuzzlePage(items, result.getNumber(), result.getSize(), result.getTotalElements());
	}

	/** 칸 구조와 한국어 힌트만 돌려준다. 정답 단어와 정의는 포함하지 않는다. */
	public PuzzleView detail(Long id) {
		Puzzle puzzle = puzzleRepository.findWithEntriesById(id)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PUZZLE_NOT_FOUND", "puzzle not found"));
		List<EntryView> entries = puzzle.getEntries().stream()
				.map(e -> new EntryView(e.getId(), e.getNumber(), e.getDirection(), e.getStartRow(), e.getStartCol(),
						e.getWord().getEnglish().length(), e.getWord().getKorean(), e.getWord().getPartOfSpeech()))
				.toList();
		return new PuzzleView(puzzle.getId(), puzzle.getSize(), puzzle.getDifficulty(), puzzle.getTopic(),
				puzzle.getType(), gridOf(puzzle), entries);
	}

	private static List<String> gridOf(Puzzle puzzle) {
		int size = puzzle.getSize();
		char[][] cells = new char[size][size];
		for (char[] row : cells) {
			Arrays.fill(row, '#');
		}
		for (PuzzleEntry e : puzzle.getEntries()) {
			int length = e.getWord().getEnglish().length();
			for (int i = 0; i < length; i++) {
				cells[e.getStartRow() + e.getDirection().rowStep() * i][e.getStartCol()
						+ e.getDirection().colStep() * i] = '.';
			}
		}
		return Arrays.stream(cells).map(String::new).toList();
	}
}
