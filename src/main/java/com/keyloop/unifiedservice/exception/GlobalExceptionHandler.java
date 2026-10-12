package com.keyloop.unifiedservice.exception;

import com.keyloop.unifiedservice.dto.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ResourceNotFoundException.class)
	public ResponseEntity<ApiError> handleResourceNotFound(ResourceNotFoundException ex) {
		return error(HttpStatus.NOT_FOUND, ex.getMessage(), Map.of());
	}

	@ExceptionHandler(NoResourceAvailableException.class)
	public ResponseEntity<ApiError> handleNoResourceAvailable(NoResourceAvailableException ex) {
		return error(HttpStatus.CONFLICT, ex.getMessage(), Map.of());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
			fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
		}
		return error(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
	}

	private ResponseEntity<ApiError> error(HttpStatus status, String message, Map<String, String> fieldErrors) {
		ApiError body = new ApiError(status.value(), status.getReasonPhrase(), message, fieldErrors);
		return ResponseEntity.status(status).body(body);
	}
}
