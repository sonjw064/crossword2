package crossword2.progress;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import crossword2.auth.Owner;
import crossword2.common.ApiException;
import crossword2.progress.ProgressDtos.ProgressResponse;
import crossword2.progress.ProgressDtos.WrongAnswersResponse;
import crossword2.progress.ProgressService.WrongAnswerSort;

/** 내 기록과 오답노트. /api/me/** 라서 로그인(게스트 포함)이 필요하다. */
@RestController
@RequestMapping("/api/me")
public class ProgressController {

	private final ProgressService progress;

	public ProgressController(ProgressService progress) {
		this.progress = progress;
	}

	@GetMapping("/progress")
	public ProgressResponse progress(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int pageSize) {
		return progress.progress(Owner.fromJwt(jwt), page, pageSize);
	}

	@GetMapping("/wrong-answers")
	public WrongAnswersResponse wrongAnswers(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int pageSize, @RequestParam(defaultValue = "recent") String sort) {
		WrongAnswerSort order;
		try {
			order = WrongAnswerSort.valueOf(sort.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "sort must be recent or count");
		}
		return progress.wrongAnswers(Owner.fromJwt(jwt), page, pageSize, order);
	}
}
