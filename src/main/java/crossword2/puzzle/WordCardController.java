package crossword2.puzzle;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import crossword2.puzzle.PuzzleDtos.WordCard;

@RestController
@RequestMapping("/api/words")
public class WordCardController {

	private final PlayService playService;

	public WordCardController(PlayService playService) {
		this.playService = playService;
	}

	@GetMapping("/{id}")
	public WordCard card(@PathVariable Long id,
			@RequestHeader(value = PuzzleController.SESSION_HEADER, required = false) String session) {
		return playService.wordCard(id, session);
	}
}
