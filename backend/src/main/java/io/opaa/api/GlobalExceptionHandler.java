package io.opaa.api;

import io.opaa.api.dto.ErrorResponse;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidationException(
      MethodArgumentNotValidException ex) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .findFirst()
            .orElse("Validation failed");
    return ResponseEntity.badRequest()
        .body(new ErrorResponse(message, HttpStatus.BAD_REQUEST.value(), Instant.now()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleMessageNotReadableException(
      HttpMessageNotReadableException ex) {
    return ResponseEntity.badRequest()
        .body(
            new ErrorResponse(
                "Request body is missing or malformed",
                HttpStatus.BAD_REQUEST.value(),
                Instant.now()));
  }

  @ExceptionHandler(TransientAiException.class)
  public ResponseEntity<ErrorResponse> handleTransientAiException(TransientAiException ex) {
    log.warn("Transient AI service error: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(
            new ErrorResponse(
                "AI service temporarily unavailable",
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                Instant.now()));
  }

  @ExceptionHandler(NonTransientAiException.class)
  public ResponseEntity<ErrorResponse> handleNonTransientAiException(NonTransientAiException ex) {
    log.error("Non-transient AI service error: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(new ErrorResponse("AI service error", HttpStatus.BAD_GATEWAY.value(), Instant.now()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
    log.error("Unexpected error", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            new ErrorResponse(
                "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR.value(), Instant.now()));
  }
}
