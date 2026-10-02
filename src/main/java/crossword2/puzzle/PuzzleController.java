package crossword2.puzzle;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import crossword2.auth.Owner;
import crossword2.puzzle.PuzzleDtos.CheckRequest;
import crossword2.puzzle.PuzzleDtos.CheckResponse;
import crossword2.puzzle.PuzzleDtos.DefinitionHintResponse;
import crossword2.puzzle.PuzzleDtos.EntryRequest;
import crossword2.puzzle.PuzzleDtos.HintResponse;
import crossword2.puzzle.PuzzleDtos.PuzzleOptions;
import crossword2.puzzle.PuzzleDtos.PuzzlePage;
import crossword2.puzzle.PuzzleDtos.PuzzleView;
import crossword2.puzzle.PuzzleDtos.RevealResponse;
import crossword2.puzzle.PuzzleDtos.StartResponse;
import crossword2.word.Difficulty;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/puzzles")
public class PuzzleController {

	static final String SESSION_HEADER = "X-Play-Session";

	private final PuzzleService puzzleService;
	private final PlayService playService;

	public PuzzleController(PuzzleService puzzleService, PlayService playService) {
		this.puzzleService = puzzleService;
		this.playService = playService;
	}

	@GetMapping
	public PuzzlePage list(@RequestParam(required = false) Difficulty difficulty,
			@RequestParam(required = false) String topic, @RequestParam(required = false) Integer size,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int pageSize) {
		return puzzleService.list(difficulty, topic, size, page, pageSize);
	}

	@GetMapping("/options")
	public PuzzleOptions options() {
		return puzzleService.options();
	}

	@GetMapping("/{id}")
	public PuzzleView detail(@PathVariable Long id) {
		return puzzleService.detail(id);
	}

	@PostMapping("/{id}/start")
	public StartResponse start(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
		return playService.start(id, Owner.fromJwt(jwt));
	}

	@PostMapping("/{id}/check")
	public CheckResponse check(@PathVariable Long id,
			@RequestHeader(value = SESSION_HEADER, required = false) String session,
			@Valid @RequestBody CheckRequest request, @AuthenticationPrincipal Jwt jwt) {
		return playService.check(id, session, request, Owner.fromJwt(jwt));
	}

	@PostMapping("/{id}/hint")
	public HintResponse hint(@PathVariable Long id,
			@RequestHeader(value = SESSION_HEADER, required = false) String session,
			@Valid @RequestBody EntryRequest request, @AuthenticationPrincipal Jwt jwt) {
		return playService.hint(id, session, request.entryId(), Owner.fromJwt(jwt));
	}

	@PostMapping("/{id}/definition-hint")
	public DefinitionHintResponse definitionHint(@PathVariable Long id,
			@RequestHeader(value = SESSION_HEADER, required = false) String session,
			@Valid @RequestBody EntryRequest request, @AuthenticationPrincipal Jwt jwt) {
		return playService.definitionHint(id, session, request.entryId(), Owner.fromJwt(jwt));
	}

	@PostMapping("/{id}/reveal")
	public RevealResponse reveal(@PathVariable Long id,
			@RequestHeader(value = SESSION_HEADER, required = false) String session,
			@AuthenticationPrincipal Jwt jwt) {
		return playService.reveal(id, session, Owner.fromJwt(jwt));
	}
}
