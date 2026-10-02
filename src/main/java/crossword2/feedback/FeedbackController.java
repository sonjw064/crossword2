package crossword2.feedback;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import crossword2.auth.Owner;
import crossword2.feedback.FeedbackDtos.AttachmentFile;
import crossword2.feedback.FeedbackDtos.CreateFeedbackRequest;
import crossword2.feedback.FeedbackDtos.FeedbackCreated;
import crossword2.feedback.FeedbackDtos.MineResponse;
import crossword2.feedback.FeedbackDtos.ReplyView;
import crossword2.feedback.FeedbackDtos.UnreadCount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** 문의/피드백. 로그인(게스트 포함)이 필요하다. 목록과 읽음 처리는 회원 전용이다. */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

	private final FeedbackService service;

	public FeedbackController(FeedbackService service) {
		this.service = service;
	}

	/** 스크린샷을 함께 보내는 방식: {@code data}(JSON)와 선택 {@code screenshot}(PNG/JPEG, 2MB 이하) 파트. */
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public FeedbackCreated createWithScreenshot(@RequestPart("data") @Valid CreateFeedbackRequest data,
			@RequestPart(value = "screenshot", required = false) MultipartFile screenshot,
			@AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
		return service.create(Owner.fromJwt(jwt), data, screenshot, http.getHeader(HttpHeaders.USER_AGENT),
				http.getRemoteAddr());
	}

	/** 첨부 없이 JSON으로만 보내는 방식. */
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public FeedbackCreated create(@RequestBody @Valid CreateFeedbackRequest data, @AuthenticationPrincipal Jwt jwt,
			HttpServletRequest http) {
		return service.create(Owner.fromJwt(jwt), data, null, http.getHeader(HttpHeaders.USER_AGENT),
				http.getRemoteAddr());
	}

	@GetMapping("/mine")
	public MineResponse mine(@AuthenticationPrincipal Jwt jwt) {
		return service.mine(Owner.fromJwt(jwt));
	}

	@GetMapping("/unread-count")
	public UnreadCount unreadCount(@AuthenticationPrincipal Jwt jwt) {
		return new UnreadCount(service.unreadCount(Owner.fromJwt(jwt)));
	}

	@PatchMapping("/replies/{id}/read")
	public ReplyView markRead(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
		return service.markRead(Owner.fromJwt(jwt), id);
	}

	/** 저장된 Content-Type으로만 내려주고 스크립트 실행을 막는 헤더를 붙인다. */
	@GetMapping("/{id}/attachment")
	public ResponseEntity<byte[]> attachment(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
		AttachmentFile file = service.attachment(Owner.fromJwt(jwt), id);
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(file.contentType()))
				.cacheControl(CacheControl.noStore())
				.header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"screenshot\"")
				.header("X-Content-Type-Options", "nosniff")
				.header("Content-Security-Policy", "sandbox; default-src 'none'")
				.body(file.bytes());
	}
}
