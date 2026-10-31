package io.opaa.api;

import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.RateLimitException;
import io.opaa.api.dto.ErrorResponse;
import io.opaa.api.dto.FieldError;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.BadGatewayException;
import io.opaa.common.ConflictException;
import io.opaa.common.FailureDescriber;
import io.opaa.common.FieldValidationException;
import io.opaa.common.NotFoundException;
import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.ServiceUnavailableException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.common.UnauthorizedException;
import io.opaa.common.ValidationException;
import io.opaa.knowledge.UploadProperties;
import io.opaa.ratelimit.RateLimitAuthentication;
import io.opaa.security.CredentialsEncryptionKeyMissingException;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * <b>Declared without a selector on purpose:</b> any selector makes Spring skip exceptions raised
 * before a handler is resolved, so the unmapped-path 404 and the 405 would lose the German
 * envelope. The Actuator mappings it covers as well negotiate their bodies like any other ({@link
 * ErrorBodyNegotiator}).
 */
@RestControllerAdvice
public class GlobalExceptionHandler implements FailureDescriber {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  // SQLSTATE codes of class 23 (integrity constraint violation), see SQL standard / PostgreSQL.
  private static final String SQLSTATE_NOT_NULL_VIOLATION = "23502";
  private static final String SQLSTATE_FOREIGN_KEY_VIOLATION = "23503";
  private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";
  private static final String SQLSTATE_CHECK_VIOLATION = "23514";

  // Instantiated directly to keep it out of the Spring context and avoid
  // forcing every @WebMvcTest to import the bean.
  private final ErrorSanitizer errorSanitizer = new ErrorSanitizer();

  // Kept out of the Spring context for the same reason as errorSanitizer above.
  private final ErrorBodyNegotiator errorBodyNegotiator = new ErrorBodyNegotiator();

  private static final ExceptionHandlerMethodResolver HANDLER_METHODS =
      new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

  private final UploadProperties uploadProperties;
  private final Clock clock;

  public GlobalExceptionHandler(UploadProperties uploadProperties, Clock clock) {
    this.uploadProperties = uploadProperties;
    this.clock = clock;
  }

  /**
   * Every branch, the 406 included, attaches the envelope only when the caller's {@code Accept}
   * lets it be written; an unwritable one would be discarded with a stacktrace. A branch with its
   * own builder ({@code Allow}, {@code Retry-After}) hands it here ({@code
   * GlobalExceptionHandlerBodyFunnelTest}).
   */
  private ResponseEntity<ErrorResponse> respond(
      Throwable failure, ResponseEntity.BodyBuilder builder, ErrorResponse body) {
    rethrowIfCommitted(failure);
    return acceptsErrorBody() ? builder.body(body) : builder.build();
  }

  /**
   * A started body (a streamed download) can take no answer any more. A write the response refused
   * goes back unchanged: Spring logs the client that went away at {@code DEBUG} and completes the
   * request. Every other failure must break the connection so that the truncated body does not end
   * cleanly: it goes back unchanged unless Spring would take it for a client that went away (a read
   * failure such as {@code EOFException}); then a failure of this handler, carrying no cause, makes
   * Spring pass the original on to the container, which logs it and breaks the connection.
   */
  private static void rethrowIfCommitted(Throwable failure) {
    if (!responseCommitted()) {
      return;
    }
    if (responseRefusedWrite(failure)
        || !DisconnectedClientHelper.isClientDisconnectedException(failure)) {
      throw GlobalExceptionHandler.<RuntimeException>sneakyThrow(failure);
    }
    throw new IllegalStateException(
        "A committed response failed for a reason other than its client");
  }

  private static boolean responseRefusedWrite(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof ClientAbortException
          || cause instanceof AsyncRequestNotUsableException) {
        return true;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> T sneakyThrow(Throwable failure) throws T {
    throw (T) failure;
  }

  private static boolean responseCommitted() {
    return RequestContextHolder.getRequestAttributes()
            instanceof ServletRequestAttributes servletAttributes
        && servletAttributes.getResponse() != null
        && servletAttributes.getResponse().isCommitted();
  }

  private ResponseEntity<ErrorResponse> respond(
      Throwable failure, HttpStatusCode status, ErrorResponse body) {
    return respond(failure, ResponseEntity.status(status), body);
  }

  private ResponseEntity<ErrorResponse> respond(
      Throwable failure, HttpStatusCode status, String message) {
    return respond(failure, status, error(status, message));
  }

  /** The envelope every branch answers with: the message, the numeric status, and the moment. */
  private ErrorResponse error(HttpStatusCode status, String message) {
    return new ErrorResponse(message, status.value(), Instant.now(clock));
  }

  /**
   * Without a bound request - a unit test invoking a branch directly - a body is written. The
   * direction is deliberate rather than an enumeration of cases: the opposite one would silently
   * strip the body from every direct call, the 45 assertions of {@code GlobalExceptionHandlerTest}
   * among them.
   */
  private boolean acceptsErrorBody() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes servletAttributes) {
      return errorBodyNegotiator.acceptsErrorBody(servletAttributes.getRequest());
    }
    return true;
  }

  /**
   * The envelope this handler answers {@code failure} with, for a response that is already under
   * way and can carry it only inside its body (the streamed answer): the same branch, message and
   * status as a response not yet begun, without its headers. The branch runs as for a request
   * without a bound response, so that it describes instead of breaking the connection.
   */
  @Override
  public ErrorResponse describe(Throwable failure, HttpServletRequest request) {
    RequestAttributes bound = RequestContextHolder.getRequestAttributes();
    RequestContextHolder.resetRequestAttributes();
    try {
      ResponseEntity<ErrorResponse> answer = dispatch(failure, request);
      if (answer != null && answer.getBody() != null) {
        return answer.getBody();
      }
      HttpStatusCode status =
          answer != null
              ? answer.getStatusCode()
              : failure instanceof ResponseStatusException statusException
                  ? statusException.getStatusCode()
                  : HttpStatus.INTERNAL_SERVER_ERROR;
      return error(status, defaultMessageFor(status));
    } finally {
      RequestContextHolder.setRequestAttributes(bound);
    }
  }

  @SuppressWarnings("unchecked")
  private ResponseEntity<ErrorResponse> dispatch(Throwable failure, HttpServletRequest request) {
    Method handler = HANDLER_METHODS.resolveMethodByThrowable(failure);
    if (handler == null) {
      return handleGenericException(
          failure instanceof Exception exception ? exception : new IllegalStateException(failure));
    }
    Object[] arguments = new Object[handler.getParameterCount()];
    Class<?>[] types = handler.getParameterTypes();
    for (int i = 0; i < types.length; i++) {
      arguments[i] = types[i] == HttpServletRequest.class ? request : matching(failure, types[i]);
    }
    try {
      return (ResponseEntity<ErrorResponse>) handler.invoke(this, arguments);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    } catch (InvocationTargetException e) {
      throw sneakyThrow(e.getCause());
    }
  }

  private static Object matching(Throwable failure, Class<?> type) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (type.isInstance(cause)) {
        return cause;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return null;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidationException(
      MethodArgumentNotValidException ex) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .findFirst()
            .orElse("Validierung fehlgeschlagen");
    return respond(ex, HttpStatus.BAD_REQUEST, message);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleMessageNotReadableException(
      HttpMessageNotReadableException ex) {
    return respond(ex, HttpStatus.BAD_REQUEST, "Der Anfragetext fehlt oder ist fehlerhaft");
  }

  /**
   * The message of an {@link IllegalArgumentException} is a developer's text, written in English by
   * the JDK or a library as often as not: it goes to the log, the caller gets a German one.
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
    log.warn(
        "Request rejected with illegal argument: {}", errorSanitizer.sanitize(ex.getMessage()));
    log.debug("Request rejected with illegal argument", ex);
    return respond(ex, HttpStatus.BAD_REQUEST, "Die Anfrage enthält ungültige Angaben");
  }

  /** A missing required {@code @RequestParam} is the caller's error: 400 naming the parameter. */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ErrorResponse> handleMissingServletRequestParameterException(
      MissingServletRequestParameterException ex) {
    return respond(ex, HttpStatus.BAD_REQUEST, "Pflichtparameter fehlt: " + ex.getParameterName());
  }

  /** A malformed parameter, e.g. {@code ?from=gestern}, is the caller's error: 400. */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatchException(
      MethodArgumentTypeMismatchException ex) {
    return respond(ex, HttpStatus.BAD_REQUEST, "Ungültiger Wert für Parameter: " + ex.getName());
  }

  @ExceptionHandler(TransientAiException.class)
  public ResponseEntity<ErrorResponse> handleTransientAiException(TransientAiException ex) {
    log.warn("Transient AI service error: {}", errorSanitizer.sanitize(ex.getMessage()));
    return respond(ex, HttpStatus.SERVICE_UNAVAILABLE, "KI-Dienst vorübergehend nicht verfügbar");
  }

  @ExceptionHandler(NonTransientAiException.class)
  public ResponseEntity<ErrorResponse> handleNonTransientAiException(NonTransientAiException ex) {
    log.error("Non-transient AI service error: {}", errorSanitizer.sanitize(ex.getMessage()));
    return respond(ex, HttpStatus.BAD_GATEWAY, "Fehler im KI-Dienst");
  }

  /**
   * The OpenAI SDK's connection failure ({@link OpenAIIoException}) and exhausted retry ({@link
   * OpenAIRetryableException}) extend no Spring AI type; they get the same 503 as {@link
   * #handleTransientAiException}.
   */
  @ExceptionHandler({OpenAIIoException.class, OpenAIRetryableException.class})
  public ResponseEntity<ErrorResponse> handleOpenAiTransientException(RuntimeException ex) {
    log.warn("Transient AI service error: {}", errorSanitizer.sanitize(ex.getMessage()));
    return respond(ex, HttpStatus.SERVICE_UNAVAILABLE, "KI-Dienst vorübergehend nicht verfügbar");
  }

  /**
   * The provider answered with an error status: {@code 429} and {@code 5xx} are transient (503, a
   * provider's {@code Retry-After} passed through), every other status needs an operator (502),
   * with the messages of the two Spring AI handlers above.
   */
  @ExceptionHandler(OpenAIServiceException.class)
  public ResponseEntity<ErrorResponse> handleOpenAiServiceException(OpenAIServiceException ex) {
    int statusCode = ex.statusCode();
    if (statusCode == HttpStatus.TOO_MANY_REQUESTS.value() || statusCode >= 500) {
      log.warn(
          "Transient AI service error ({}): {}",
          statusCode,
          errorSanitizer.sanitize(ex.getMessage()));
      ResponseEntity.BodyBuilder responseBuilder =
          ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE);
      // A provider's Retry-After on a 429 is passed through to the caller.
      if (ex instanceof RateLimitException rateLimitException) {
        List<String> retryAfter = rateLimitException.headers().values(HttpHeaders.RETRY_AFTER);
        if (!retryAfter.isEmpty()) {
          responseBuilder =
              responseBuilder.header(HttpHeaders.RETRY_AFTER, retryAfter.toArray(new String[0]));
        }
      }
      return respond(
          ex,
          responseBuilder,
          error(HttpStatus.SERVICE_UNAVAILABLE, "KI-Dienst vorübergehend nicht verfügbar"));
    }
    log.error(
        "Non-transient AI service error ({}): {}",
        statusCode,
        errorSanitizer.sanitize(ex.getMessage()));
    return respond(ex, HttpStatus.BAD_GATEWAY, "Fehler im KI-Dienst");
  }

  /**
   * Every other {@link OpenAIException}, chiefly an answer in an unexpected shape from an
   * OpenAI-compatible server: the provider answered, but not usefully (502). The more specific
   * handlers above win by hierarchy distance.
   */
  @ExceptionHandler(OpenAIException.class)
  public ResponseEntity<ErrorResponse> handleOpenAiException(OpenAIException ex) {
    log.error("Unexpected AI service error: {}", errorSanitizer.sanitize(ex.getMessage()));
    return respond(ex, HttpStatus.BAD_GATEWAY, "Fehler im KI-Dienst");
  }

  /**
   * An unmapped path is the caller's error: 404, logged at {@code DEBUG} without a stacktrace, so
   * scanners probing {@code /wp-admin} do not flood the log.
   */
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<ErrorResponse> handleNoResourceFoundException(Exception ex) {
    log.debug("No handler found for request: {}", errorSanitizer.sanitize(ex.getMessage()));
    return respond(ex, HttpStatus.NOT_FOUND, "Die angeforderte Ressource wurde nicht gefunden");
  }

  /**
   * 405 with the {@code Allow} header RFC 9110, section 15.5.6 requires, logged at {@code DEBUG}:
   * any caller can raise it without a session. {@link #handleGenericException} would otherwise
   * claim it before Spring's own resolver.
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleHttpRequestMethodNotSupportedException(
      HttpRequestMethodNotSupportedException ex) {
    log.debug("Unsupported request method: {}", errorSanitizer.sanitize(ex.getMessage()));
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
    Set<HttpMethod> supportedMethods = ex.getSupportedHttpMethods();
    if (supportedMethods != null && !supportedMethods.isEmpty()) {
      response.allow(supportedMethods.toArray(new HttpMethod[0]));
    }
    return respond(
        ex,
        response,
        error(
            HttpStatus.METHOD_NOT_ALLOWED,
            "Die HTTP-Methode wird für diese Ressource nicht unterstützt"));
  }

  /** The media-type half of {@link #handleHttpRequestMethodNotSupportedException}. */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleHttpMediaTypeNotSupportedException(
      HttpMediaTypeNotSupportedException ex) {
    log.debug("Unsupported media type: {}", errorSanitizer.sanitize(ex.getMessage()));
    return respond(
        ex, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Der Inhaltstyp der Anfrage wird nicht unterstützt");
  }

  /**
   * 406 without a stacktrace, which anyone could raise per request with {@code Accept:
   * application/xml}. {@link #respond} drops the body when the envelope is not writable either and
   * sends it when only a mapping's {@code produces=} did not match.
   */
  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<ErrorResponse> handleHttpMediaTypeNotAcceptableException(
      HttpMediaTypeNotAcceptableException ex) {
    log.debug("Unacceptable response format: {}", errorSanitizer.sanitize(ex.getMessage()));
    return respond(
        ex, HttpStatus.NOT_ACCEPTABLE, "Das angeforderte Antwortformat wird nicht unterstützt");
  }

  @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDeniedException(
      org.springframework.security.access.AccessDeniedException ex) {
    return respond(ex, HttpStatus.FORBIDDEN, "Zugriff verweigert");
  }

  /**
   * Maps the domain exception hierarchy in {@code io.opaa.common} to the identical response body
   * {@link #handleResponseStatusException} produces for {@link ResponseStatusException} - status
   * and {@link Exception#getMessage()} as the reason.
   */
  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<ErrorResponse> handleNotFoundException(NotFoundException ex) {
    ErrorResponse body = error(HttpStatus.NOT_FOUND, ex.getMessage());
    if (ex.getCode() != null) {
      body.setCode(ex.getCode());
    }
    return respond(ex, HttpStatus.NOT_FOUND, body);
  }

  /**
   * A refusal may carry a stable {@code code} the client acts on (e.g. {@code CAPABILITY_REQUIRED})
   * and a {@code reason} qualifying it.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleDomainAccessDeniedException(AccessDeniedException ex) {
    ErrorResponse body = error(HttpStatus.FORBIDDEN, ex.getMessage());
    if (ex.getCode() != null) {
      body.setCode(ex.getCode());
    }
    if (ex.getReason() != null) {
      body.setReason(ex.getReason());
    }
    return respond(ex, HttpStatus.FORBIDDEN, body);
  }

  /** A conflict may carry a stable {@code code} the client acts on (ADR-0033, Entscheidung 4). */
  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<ErrorResponse> handleConflictException(ConflictException ex) {
    ErrorResponse body = error(HttpStatus.CONFLICT, ex.getMessage());
    if (ex.getCode() != null) {
      body.setCode(ex.getCode());
    }
    return respond(ex, HttpStatus.CONFLICT, body);
  }

  /** A refusal may carry a stable {@code code} the client acts on (e.g. {@code TOKEN_INVALID}). */
  @ExceptionHandler(ValidationException.class)
  public ResponseEntity<ErrorResponse> handleValidationException(ValidationException ex) {
    ErrorResponse body = error(HttpStatus.BAD_REQUEST, ex.getMessage());
    if (ex.getCode() != null) {
      body.setCode(ex.getCode());
    }
    return respond(ex, HttpStatus.BAD_REQUEST, body);
  }

  /**
   * The field-level sibling of {@link #handleValidationException(ValidationException)}: the same
   * {@code 400} envelope, plus {@code fieldErrors} - one entry per field and violated rule with a
   * stable code - so a form can mark the fields instead of showing one sentence.
   */
  @ExceptionHandler(FieldValidationException.class)
  public ResponseEntity<ErrorResponse> handleFieldValidationException(FieldValidationException ex) {
    ErrorResponse body = error(HttpStatus.BAD_REQUEST, ex.getMessage());
    body.setFieldErrors(
        ex.fieldErrors().stream()
            .map(error -> new FieldError(error.field(), error.code(), error.message()))
            .toList());
    return respond(ex, HttpStatus.BAD_REQUEST, body);
  }

  @ExceptionHandler(UnauthorizedException.class)
  public ResponseEntity<ErrorResponse> handleUnauthorizedException(UnauthorizedException ex) {
    return respond(ex, HttpStatus.UNAUTHORIZED, ex.getMessage());
  }

  @ExceptionHandler(PayloadTooLargeException.class)
  public ResponseEntity<ErrorResponse> handlePayloadTooLargeException(PayloadTooLargeException ex) {
    return respond(ex, HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage());
  }

  @ExceptionHandler(TooManyRequestsException.class)
  public ResponseEntity<ErrorResponse> handleTooManyRequestsException(
      TooManyRequestsException ex, HttpServletRequest request) {
    RateLimitAuthentication.markRefusedByLaterLimit(request);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
    if (ex.retryAfterSeconds() > 0) {
      response.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
    }
    ErrorResponse body = error(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    if (ex.getCode() != null) {
      body.setCode(ex.getCode());
    }
    return respond(ex, response, body);
  }

  @ExceptionHandler(ServiceUnavailableException.class)
  public ResponseEntity<ErrorResponse> handleServiceUnavailableException(
      ServiceUnavailableException ex) {
    if (ex.getCode() != null) {
      ErrorResponse body = error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
      body.setCode(ex.getCode());
      return respond(ex, HttpStatus.SERVICE_UNAVAILABLE, body);
    }
    if (ex.retryAfterSeconds() > 0) {
      return respond(
          ex,
          ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
              .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds())),
          error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()));
    }
    log.error("Server error raised by application", ex);
    return respond(ex, HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
  }

  @ExceptionHandler(BadGatewayException.class)
  public ResponseEntity<ErrorResponse> handleBadGatewayException(BadGatewayException ex) {
    return respond(ex, HttpStatus.BAD_GATEWAY, ex.getMessage());
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponse> handleResponseStatusException(ResponseStatusException ex) {
    HttpStatusCode status = ex.getStatusCode();
    if (status.is5xxServerError()) {
      log.error("Server error raised by application", ex);
    }
    String message = ex.getReason() != null ? ex.getReason() : defaultMessageFor(status);
    return respond(ex, status, message);
  }

  /**
   * The container's multipart limit, bound to the same value as {@code opaa.upload.max-file-size}:
   * 413 with a German message. The figure comes from {@link UploadProperties}, because {@code
   * ex.getMaxUploadSize()} usually reports {@code -1}.
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceededException(
      MaxUploadSizeExceededException ex) {
    String message =
        "Die Datei ist zu groß. Erlaubt sind höchstens "
            + (uploadProperties.maxFileSize() / (1024 * 1024))
            + " MB.";
    return respond(ex, HttpStatus.PAYLOAD_TOO_LARGE, message);
  }

  /** A multipart request without the expected part, e.g. {@code file}: 400 naming the part. */
  @ExceptionHandler(MissingServletRequestPartException.class)
  public ResponseEntity<ErrorResponse> handleMissingServletRequestPartException(
      MissingServletRequestPartException ex) {
    String message = "Der Anfrageteil '" + ex.getRequestPartName() + "' fehlt";
    return respond(ex, HttpStatus.BAD_REQUEST, message);
  }

  /**
   * Every other multipart failure, e.g. a malformed body or an aborted transfer: 400. The two more
   * specific handlers above win.
   */
  @ExceptionHandler(MultipartException.class)
  public ResponseEntity<ErrorResponse> handleMultipartException(MultipartException ex) {
    log.warn("Multipart request could not be processed", ex);
    return respond(
        ex, HttpStatus.BAD_REQUEST, "Die hochgeladene Datei konnte nicht verarbeitet werden");
  }

  /**
   * {@code OPAA_CREDENTIALS_ENCRYPTION_KEY} is missing or invalid: 503, so an operator sees "set
   * the key". A wrapped occurrence is unwrapped by {@link #handleGenericException}.
   */
  @ExceptionHandler(CredentialsEncryptionKeyMissingException.class)
  public ResponseEntity<ErrorResponse> handleCredentialsEncryptionKeyMissingException(
      CredentialsEncryptionKeyMissingException ex) {
    log.error("Credentials encryption key missing or invalid", ex);
    return respond(ex, HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
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
    return respond(ex, status, message);
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
    // Before logging: a client that went away is no unexpected error, any other failure the
    // container logs itself.
    rethrowIfCommitted(ex);
    CredentialsEncryptionKeyMissingException credentialsCause =
        findCause(ex, CredentialsEncryptionKeyMissingException.class);
    if (credentialsCause != null) {
      return handleCredentialsEncryptionKeyMissingException(credentialsCause);
    }
    // A com.openai.errors.* exception wrapped by something else (e.g. Spring AI's own retry/advisor
    // machinery) would otherwise still land here uncaught by any of the dedicated handlers above,
    // which only match against the exception actually thrown to this method - not its cause chain.
    OpenAIException openAiCause = findCause(ex, OpenAIException.class);
    if (openAiCause != null) {
      if (openAiCause instanceof OpenAIServiceException serviceException) {
        return handleOpenAiServiceException(serviceException);
      }
      if (openAiCause instanceof OpenAIIoException
          || openAiCause instanceof OpenAIRetryableException) {
        return handleOpenAiTransientException(openAiCause);
      }
      return handleOpenAiException(openAiCause);
    }
    log.error("Unexpected error", ex);
    return respond(ex, HttpStatus.INTERNAL_SERVER_ERROR, "Interner Serverfehler");
  }

  private <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (type.isInstance(cause)) {
        return type.cast(cause);
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return null;
  }
}
