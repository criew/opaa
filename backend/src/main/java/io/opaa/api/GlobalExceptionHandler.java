package io.opaa.api;

import io.opaa.api.dto.ErrorResponse;
import io.opaa.library.UploadProperties;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
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

  private final UploadProperties uploadProperties;

  public GlobalExceptionHandler(UploadProperties uploadProperties) {
    this.uploadProperties = uploadProperties;
  }

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

  /**
   * The container-level counterpart to {@code LibraryDocumentService}'s own {@code
   * opaa.upload.max-file-size} check (#420 code review, finding 2): {@code
   * spring.servlet.multipart.max-file-size}/{@code max-request-size} are bound to the exact same
   * configured value (application.yml), so this fires only for the same limit that check already
   * enforces, or - because multipart request framing carries a little overhead beyond the raw file
   * bytes - for a request just over {@code max-request-size} at a file size just under it. Without
   * this handler, {@code ExceptionHandlerExceptionResolver} never reaches {@code
   * DefaultHandlerExceptionResolver}'s own multipart handling (it runs first and {@code
   * handleGenericException} below - the last-resort {@code Exception} handler - would otherwise
   * catch this first), and the response was a bare {@code 500} instead of the required {@code 413}
   * with a German message.
   *
   * <p>The reported limit comes from {@link UploadProperties} rather than {@code
   * ex.getMaxUploadSize()} (#420 second code review round, nit 2): {@code
   * StandardServletMultipartResolver} only populates that field when the container's own error
   * message happens to match its "size ... exceed" heuristic, and in practice usually reports
   * {@code -1} instead - which would silently drop the figure from the message the acceptance
   * criteria ask for. {@link UploadProperties} is the actual configured limit either way, since
   * both this handler and the container's own check are bound to the same value.
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceededException(
      MaxUploadSizeExceededException ex) {
    String message =
        "Die Datei ist zu gross. Erlaubt sind hoechstens "
            + (uploadProperties.maxFileSize() / (1024 * 1024))
            + " MB.";
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(new ErrorResponse(message, HttpStatus.PAYLOAD_TOO_LARGE.value(), Instant.now()));
  }

  /**
   * Thrown when a multipart request is missing the expected part (e.g. {@code file} on the upload
   * endpoint) or names it under the wrong field - see {@link #handleMaxUploadSizeExceededException}
   * for why this needs its own handler rather than falling through to {@code
   * handleGenericException}'s {@code 500}.
   */
  @ExceptionHandler(MissingServletRequestPartException.class)
  public ResponseEntity<ErrorResponse> handleMissingServletRequestPartException(
      MissingServletRequestPartException ex) {
    String message = "Der Anfrageteil '" + ex.getRequestPartName() + "' fehlt";
    return ResponseEntity.badRequest()
        .body(new ErrorResponse(message, HttpStatus.BAD_REQUEST.value(), Instant.now()));
  }

  /**
   * The remaining multipart failures that are neither a size overrun nor a missing part - a
   * malformed body, an aborted transfer (#420 second code review round, nit 2). {@code
   * StandardServletMultipartResolver} only throws the more specific {@link
   * MaxUploadSizeExceededException} when the container's error message happens to match its own
   * "size ... exceed" heuristic; anything else surfaces as a bare {@link MultipartException}, which
   * - without this handler, declared after the two more specific ones above so Spring still prefers
   * those - fell through to {@link #handleGenericException}'s {@code 500}.
   */
  @ExceptionHandler(MultipartException.class)
  public ResponseEntity<ErrorResponse> handleMultipartException(MultipartException ex) {
    log.warn("Multipart request could not be processed", ex);
    return ResponseEntity.badRequest()
        .body(
            new ErrorResponse(
                "Die hochgeladene Datei konnte nicht verarbeitet werden",
                HttpStatus.BAD_REQUEST.value(),
                Instant.now()));
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
