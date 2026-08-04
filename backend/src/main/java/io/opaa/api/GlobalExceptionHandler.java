package io.opaa.api;

import io.opaa.api.dto.ErrorResponse;
import java.sql.SQLException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  // SQLSTATE codes of class 23 (integrity constraint violation), see SQL standard / PostgreSQL.
  private static final String SQLSTATE_NOT_NULL_VIOLATION = "23502";
  private static final String SQLSTATE_FOREIGN_KEY_VIOLATION = "23503";
  private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";
  private static final String SQLSTATE_CHECK_VIOLATION = "23514";

  // Instantiated directly to keep it out of the Spring context and avoid
  // forcing every @WebMvcTest to import the bean.
  private final ErrorSanitizer errorSanitizer = new ErrorSanitizer();

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidationException(
      MethodArgumentNotValidException ex) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .findFirst()
            .orElse("Validierung fehlgeschlagen");
    return ResponseEntity.badRequest()
        .body(new ErrorResponse(message, HttpStatus.BAD_REQUEST.value(), Instant.now()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleMessageNotReadableException(
      HttpMessageNotReadableException ex) {
    return ResponseEntity.badRequest()
        .body(
            new ErrorResponse(
                "Der Anfragetext fehlt oder ist fehlerhaft",
                HttpStatus.BAD_REQUEST.value(),
                Instant.now()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
    return ResponseEntity.badRequest()
        .body(new ErrorResponse(ex.getMessage(), HttpStatus.BAD_REQUEST.value(), Instant.now()));
  }

  @ExceptionHandler(TransientAiException.class)
  public ResponseEntity<ErrorResponse> handleTransientAiException(TransientAiException ex) {
    log.warn("Transient AI service error: {}", errorSanitizer.sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(
            new ErrorResponse(
                "KI-Dienst vorübergehend nicht verfügbar",
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                Instant.now()));
  }

  @ExceptionHandler(NonTransientAiException.class)
  public ResponseEntity<ErrorResponse> handleNonTransientAiException(NonTransientAiException ex) {
    log.error("Non-transient AI service error: {}", errorSanitizer.sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(
            new ErrorResponse(
                "Fehler im KI-Dienst", HttpStatus.BAD_GATEWAY.value(), Instant.now()));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new ErrorResponse("Zugriff verweigert", HttpStatus.FORBIDDEN.value(), Instant.now()));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponse> handleResponseStatusException(ResponseStatusException ex) {
    HttpStatusCode status = ex.getStatusCode();
    if (status.is5xxServerError()) {
      log.error("Server error raised by application", ex);
    }
    String message = ex.getReason() != null ? ex.getReason() : defaultMessageFor(status);
    return ResponseEntity.status(status)
        .body(new ErrorResponse(message, status.value(), Instant.now()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ErrorResponse> handleDataIntegrityViolationException(
      DataIntegrityViolationException ex) {
    // The constraint name belongs in the log, never in the response.
    log.warn("Data integrity violation", ex);

    String sqlState = findSqlState(ex);
    HttpStatus status = HttpStatus.CONFLICT;
    String message;
    switch (sqlState == null ? "" : sqlState) {
      case SQLSTATE_UNIQUE_VIOLATION -> message = "Ein Eintrag mit diesen Werten existiert bereits";
      case SQLSTATE_FOREIGN_KEY_VIOLATION ->
          message =
              "Der Datensatz wird noch verwendet und kann nicht geändert oder gelöscht werden";
      case SQLSTATE_NOT_NULL_VIOLATION, SQLSTATE_CHECK_VIOLATION -> {
        // Malformed input rather than a conflict with existing data.
        status = HttpStatus.BAD_REQUEST;
        message = "Die übergebenen Daten sind unvollständig oder ungültig";
      }
      default -> message = "Die Aktion widerspricht bestehenden Daten und wurde nicht ausgeführt";
    }
    return ResponseEntity.status(status)
        .body(new ErrorResponse(message, status.value(), Instant.now()));
  }

  private String defaultMessageFor(HttpStatusCode status) {
    if (status.is5xxServerError()) {
      return "Interner Serverfehler";
    }
    return "Die Anfrage konnte nicht verarbeitet werden";
  }

  private String findSqlState(Throwable ex) {
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException && sqlException.getSQLState() != null) {
        return sqlException.getSQLState();
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return null;
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
    log.error("Unexpected error", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            new ErrorResponse(
                "Interner Serverfehler", HttpStatus.INTERNAL_SERVER_ERROR.value(), Instant.now()));
  }
}
