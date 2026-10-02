package crossword2.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		return badRequest("INVALID_PARAMETER", "invalid value for parameter '" + e.getName() + "'");
	}

	private static ResponseEntity<ErrorResponse> badRequest(String code, String message) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(code, message));
	}
}
