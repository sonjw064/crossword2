package crossword2.puzzle;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import crossword2.auth.AccountLock;
import crossword2.auth.Owner;
import crossword2.common.ApiException;
import crossword2.puzzle.PuzzleDtos.AnswerItem;
import crossword2.puzzle.PuzzleDtos.AnswerStatus;
import crossword2.puzzle.PuzzleDtos.CheckRequest;
import crossword2.puzzle.PuzzleDtos.CheckResponse;
import crossword2.puzzle.PuzzleDtos.DefinitionHintResponse;
import crossword2.puzzle.PuzzleDtos.EntryResult;
import crossword2.puzzle.PuzzleDtos.HintResponse;
import crossword2.puzzle.PuzzleDtos.RevealResponse;
import crossword2.puzzle.PuzzleDtos.RevealedEntry;
import crossword2.puzzle.PuzzleDtos.StartResponse;
import crossword2.puzzle.PuzzleDtos.WordCard;
import crossword2.word.Word;
import crossword2.word.WordRepository;

/** 풀이 세션을 기준으로 채점, 힌트, 정답 보기, 단어 카드 접근을 서버에서 통제한다. */
@Service
@Transactional
public class PlayService {

	private final PuzzleRepository puzzleRepository;
	private final PlaySessionRepository sessionRepository;
	private final WordRepository wordRepository;
	private final AccountLock accountLock;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	public PlayService(PuzzleRepository puzzleRepository, PlaySessionRepository sessionRepository,
			WordRepository wordRepository, AccountLock accountLock, ApplicationEventPublisher events, Clock clock) {
		this.puzzleRepository = puzzleRepository;
		this.sessionRepository = sessionRepository;
		this.wordRepository = wordRepository;
		this.accountLock = accountLock;
		this.events = events;
		this.clock = clock;
	}

	/** 한 세션과 그 퍼즐의 항목들. */
	private record Play(PlaySession session, Puzzle puzzle, Map<Long, PuzzleEntry> entries) {
	}

	/** 소유자가 있으면 계정을 먼저 잠그고 쓸 수 있는 계정인지 확인한 뒤 세션을 만든다(게스트 이전과 경쟁해도 고아 세션이 생기지 않는다). */
	public StartResponse start(Long puzzleId, Owner caller) {
		if (caller != null) {
			accountLock.lockActive(caller);
		}
		Puzzle puzzle = puzzleRepository.findById(puzzleId).orElseThrow(PlayService::puzzleNotFound);
		PlaySession session = sessionRepository.save(new PlaySession(puzzle, clock.instant(), caller));
		return new StartResponse(session.getId(), puzzle.getId(), session.getStartedAt());
	}

	public CheckResponse check(Long puzzleId, String sessionHeader, CheckRequest request, Owner caller) {
		Play play = loadActive(puzzleId, sessionHeader, caller);
		PlaySession session = play.session();

		Set<Long> seenEntries = new HashSet<>();
		for (AnswerItem item : request.answers()) {
			if (!seenEntries.add(item.entryId())) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_ENTRY",
						"entryId appears more than once: " + item.entryId());
			}
		}

		List<EntryResult> results = new ArrayList<>();
		for (AnswerItem item : request.answers()) {
			PuzzleEntry entry = entryOf(play, item.entryId());
			String expected = entry.getWord().getEnglish();
			String given = item.answer().trim().toLowerCase(Locale.ROOT);
			AnswerStatus status;
			if (session.getSolvedEntryIds().contains(entry.getId())) {
				status = given.equals(expected) ? AnswerStatus.CORRECT : AnswerStatus.INCOMPLETE;
			} else if (given.length() != expected.length()) {
				status = AnswerStatus.INCOMPLETE;
			} else if (given.equals(expected)) {
				session.markSolved(entry.getId());
				status = AnswerStatus.CORRECT;
			} else {
				session.addWrong(entry.getId());
				status = AnswerStatus.WRONG;
			}
			results.add(new EntryResult(entry.getId(), status));
		}

		boolean completed = session.getSolvedEntryIds().size() == play.entries().size();
		Long elapsed = null;
		if (completed) {
			finish(play, PlayStatus.COMPLETED);
			elapsed = session.elapsedSeconds();
		}
		return new CheckResponse(results, completed, session.getWrongCount(), session.hintCount(), elapsed);
	}

	public HintResponse hint(Long puzzleId, String sessionHeader, Long entryId, Owner caller) {
		Play play = loadActive(puzzleId, sessionHeader, caller);
		PuzzleEntry entry = entryOf(play, entryId);
		if (!play.session().getSolvedEntryIds().contains(entry.getId())) {
			play.session().addLetterHint(entry.getId());
		}
		String letter = entry.getWord().getEnglish().substring(0, 1);
		return new HintResponse(entry.getId(), letter, play.session().hintCount());
	}

	public DefinitionHintResponse definitionHint(Long puzzleId, String sessionHeader, Long entryId, Owner caller) {
		Play play = loadActive(puzzleId, sessionHeader, caller);
		PuzzleEntry entry = entryOf(play, entryId);
		String definition = entry.getWord().getDefinition();
		if (definition == null || definition.isBlank()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "DEFINITION_NOT_FOUND", "no definition for this word");
		}
		if (!play.session().getSolvedEntryIds().contains(entry.getId())) {
			play.session().addDefinitionHint(entry.getId());
		}
		return new DefinitionHintResponse(entry.getId(), definition, play.session().hintCount());
	}

	/** 정답 보기(포기): 세션을 끝내고 모든 정답과 단어 카드를 돌려준다. */
	public RevealResponse reveal(Long puzzleId, String sessionHeader, Owner caller) {
		Play play = loadActive(puzzleId, sessionHeader, caller);
		PlaySession session = play.session();
		finish(play, PlayStatus.GAVE_UP);
		List<RevealedEntry> entries = play.puzzle().getEntries().stream()
				.map(e -> new RevealedEntry(e.getId(), cardOf(e.getWord())))
				.toList();
		return new RevealResponse(entries, session.getWrongCount(), session.hintCount(), session.elapsedSeconds());
	}

	/**
	 * 세션을 끝낸다. 소유자가 있으면 {@link PlayFinished}를 발행해 기록과 오답노트를 같은 트랜잭션에서 남긴다.
	 * 오답노트 대상: 틀린/힌트를 쓴 단어, 그리고 정답 보기로 끝났다면 끝까지 못 맞힌 단어.
	 */
	private void finish(Play play, PlayStatus status) {
		PlaySession session = play.session();
		session.finish(status, clock.instant());
		if (!session.hasOwner()) {
			return;
		}
		Set<Long> troubled = new HashSet<>(session.troubledEntryIds());
		if (status == PlayStatus.GAVE_UP) {
			play.entries().keySet().stream().filter(id -> !session.getSolvedEntryIds().contains(id)).forEach(troubled::add);
		}
		Set<Long> wordIds = troubled.stream().map(id -> play.entries().get(id).getWord().getId())
				.collect(Collectors.toSet());
		events.publishEvent(new PlayFinished(session.getId(), session.owner(), play.puzzle().getId(), status,
				session.elapsedSeconds(), session.hintCount(), session.getWrongCount(), session.getFinishedAt(), wordIds));
	}

	/** 이 세션에서 맞혔거나 공개된 단어만 카드를 볼 수 있다. */
	@Transactional(readOnly = true)
	public WordCard wordCard(Long wordId, String sessionHeader, Owner caller) {
		PlaySession session = findSession(sessionHeader, false, caller);
		Word word = wordRepository.findById(wordId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORD_NOT_FOUND", "word not found"));
		Puzzle puzzle = puzzleRepository.findWithEntriesById(session.getPuzzle().getId())
				.orElseThrow(PlayService::puzzleNotFound);
		boolean allowed = puzzle.getEntries().stream()
				.filter(e -> e.getWord().getId().equals(wordId))
				.anyMatch(e -> session.getStatus() != PlayStatus.IN_PROGRESS
						|| session.getSolvedEntryIds().contains(e.getId()));
		if (!allowed) {
			throw new ApiException(HttpStatus.FORBIDDEN, "CARD_NOT_AVAILABLE",
					"the word card is available only after the word is solved or revealed");
		}
		return cardOf(word);
	}

	/**
	 * 상태를 바꾸는 요청용: 먼저 계정 행을, 그 다음 세션 행을 잠근다(잠금 순서 계정 → 세션).
	 * 같은 사용자의 기록 갱신과 같은 세션의 동시 요청을 하나씩 처리한다.
	 */
	private Play loadActive(Long puzzleId, String sessionHeader, Owner caller) {
		if (caller != null) {
			accountLock.lock(caller);
		}
		PlaySession session = findSession(sessionHeader, true, caller);
		if (!session.getPuzzle().getId().equals(puzzleId)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "SESSION_MISMATCH", "session belongs to another puzzle");
		}
		if (!session.isActive()) {
			throw new ApiException(HttpStatus.CONFLICT, "SESSION_FINISHED", "this play session is already finished");
		}
		Puzzle puzzle = puzzleRepository.findWithEntriesById(puzzleId).orElseThrow(PlayService::puzzleNotFound);
		Map<Long, PuzzleEntry> entries = puzzle.getEntries().stream()
				.collect(Collectors.toMap(PuzzleEntry::getId, Function.identity()));
		return new Play(session, puzzle, entries);
	}

	private PlaySession findSession(String sessionHeader, boolean lock, Owner caller) {
		if (sessionHeader == null || sessionHeader.isBlank()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_SESSION", "X-Play-Session header is required");
		}
		UUID id;
		try {
			id = UUID.fromString(sessionHeader.trim());
		} catch (IllegalArgumentException e) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SESSION", "X-Play-Session is not a valid id");
		}
		PlaySession session = (lock ? sessionRepository.findForUpdate(id) : sessionRepository.findById(id))
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "session not found"));
		if (session.hasOwner() && !session.isOwnedBy(caller)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "SESSION_FORBIDDEN", "this session belongs to another user");
		}
		return session;
	}

	private static PuzzleEntry entryOf(Play play, Long entryId) {
		PuzzleEntry entry = play.entries().get(entryId);
		if (entry == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ENTRY", "entry does not belong to this puzzle");
		}
		return entry;
	}

	private static ApiException puzzleNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "PUZZLE_NOT_FOUND", "puzzle not found");
	}

	private static WordCard cardOf(Word w) {
		return new WordCard(w.getId(), w.getEnglish(), w.getKorean(), w.getPartOfSpeech(), w.getDefinition(),
				w.getExample());
	}
}
