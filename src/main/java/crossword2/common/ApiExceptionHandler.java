package crossword2.common;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

	public record ErrorResponse(String code, String message) {
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorResponse> handleApi(ApiException e) {
		return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(), e.getMessage()));
	}

	/** 잠금 대기 초과, 교착, 낙관적 잠금 충돌 등. 클라이언트는 같은 요청을 다시 보내면 된다. */
	@ExceptionHandler(ConcurrencyFailureException.class)
	ResponseEntity<ErrorResponse> handleConcurrency(ConcurrencyFailureException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ErrorResponse("CONCURRENT_UPDATE", "the request conflicted with another one, please retry"));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
		String detail = e.getBindingResult().getFieldErrors().stream()
				.map(f -> f.getField() + " " + f.getDefaultMessage())
				.findFirst().orElse("invalid request");
		return badRequest("VALIDATION_FAILED", detail);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
		return badRequest("MALFORMED_REQUEST", "request body is missing or malformed");
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e) {
		return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
				.body(new ErrorResponse("ATTACHMENT_TOO_LARGE", "the upload is too large"));
	}

	@ExceptionHandler({ MissingServletRequestPartException.class, MultipartException.class })
	ResponseEntity<ErrorResponse> handleBadMultipart(Exception e) {
		return badRequest("MALFORMED_REQUEST", "the multipart request is missing a part or malformed");
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ErrorResponse> handleUnsupportedMedia(HttpMediaTypeNotSupportedException e) {
		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
				.body(new ErrorResponse("UNSUPPORTED_MEDIA_TYPE", "unsupported content type"));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		return badRequest("INVALID_PARAMETER", "invalid value for parameter '" + e.getName() + "'");
	}

	private static ResponseEntity<ErrorResponse> badRequest(String code, String message) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(code, message));
	}
}
