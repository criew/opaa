package io.opaa.api;

import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.RateLimitException;
import io.opaa.api.dto.ErrorResponse;
import io.opaa.api.dto.FieldError;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.FieldValidationException;
import io.opaa.common.NotFoundException;
import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.ServiceUnavailableException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.common.UnauthorizedException;
import io.opaa.common.ValidationException;
import io.opaa.library.UploadProperties;
import io.opaa.security.CredentialsEncryptionKeyMissingException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

  // Kept out of the Spring context for the same reason as errorSanitizer above.
  private final ErrorBodyNegotiator errorBodyNegotiator = new ErrorBodyNegotiator();

  private final UploadProperties uploadProperties;

  public GlobalExceptionHandler(UploadProperties uploadProperties) {
    this.uploadProperties = uploadProperties;
  }

  /**
   * The single decision every branch routes through: the envelope is attached only when the
   * caller's {@code Accept} lets it be written (#1780). Attaching an unwritable one makes {@code
   * ExceptionHandlerExceptionResolver} log a stacktrace and discard this response altogether, so
   * the caller gets the container's 500 instead of the status meant for them. A branch with a
   * builder of its own ({@code Allow}, {@code Retry-After}) hands it here instead of calling {@code
   * builder.body(...)}.
   */
  private ResponseEntity<ErrorResponse> respond(
      ResponseEntity.BodyBuilder builder, ErrorResponse body) {
    return acceptsErrorBody() ? builder.body(body) : builder.build();
  }

  private ResponseEntity<ErrorResponse> respond(HttpStatusCode status, ErrorResponse body) {
    return respond(ResponseEntity.status(status), body);
  }

  /** Without a bound request - a unit test invoking a branch directly - a body is written. */
  private boolean acceptsErrorBody() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes servletAttributes) {
      return errorBodyNegotiator.acceptsErrorBody(servletAttributes.getRequest());
    }
    return true;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidationException(
      MethodArgumentNotValidException ex) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .findFirst()
            .orElse("Validierung fehlgeschlagen");
    return respond(
        HttpStatus.BAD_REQUEST,
        new ErrorResponse(message, HttpStatus.BAD_REQUEST.value(), Instant.now()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleMessageNotReadableException(
      HttpMessageNotReadableException ex) {
    return respond(
        HttpStatus.BAD_REQUEST,
        new ErrorResponse(
            "Der Anfragetext fehlt oder ist fehlerhaft",
            HttpStatus.BAD_REQUEST.value(),
            Instant.now()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
    return respond(
        HttpStatus.BAD_REQUEST,
        new ErrorResponse(ex.getMessage(), HttpStatus.BAD_REQUEST.value(), Instant.now()));
  }

  /**
   * #393: the four revision access paths declare their required query parameters
   * (objectType/objectId, eventType, correlationRef, the mandatory from/to time range on every one
   * of them) via plain {@code @RequestParam(required = true)} rather than a validated request DTO -
   * the first callers in this codebase to rely on that for enforcement rather than a defaulted or
   * optional parameter. Without this handler, a missing one fell through to {@link
   * #handleGenericException}'s {@code 500} instead of the 400 "eine Abfrage ohne [Bezug] wird
   * abgewiesen" acceptance criterion requires.
   */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ErrorResponse> handleMissingServletRequestParameterException(
      MissingServletRequestParameterException ex) {
    return respond(
        HttpStatus.BAD_REQUEST,
        new ErrorResponse(
            "Pflichtparameter fehlt: " + ex.getParameterName(),
            HttpStatus.BAD_REQUEST.value(),
            Instant.now()));
  }

  /**
   * #393 code review, nit 6: the sibling of {@link #handleMissingServletRequestParameterException}
   * - a *malformed* required parameter (e.g. {@code ?from=gestern} or {@code ?objectType=FOO}
   * against the #393 revision access paths) is still a caller error, not a server error, and fell
   * through to {@link #handleGenericException}'s {@code 500} without this handler.
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatchException(
      MethodArgumentTypeMismatchException ex) {
    return respond(
        HttpStatus.BAD_REQUEST,
        new ErrorResponse(
            "Ungültiger Wert für Parameter: " + ex.getName(),
            HttpStatus.BAD_REQUEST.value(),
            Instant.now()));
  }

  @ExceptionHandler(TransientAiException.class)
  public ResponseEntity<ErrorResponse> handleTransientAiException(TransientAiException ex) {
    log.warn("Transient AI service error: {}", errorSanitizer.sanitize(ex.getMessage()));
    return respond(
        HttpStatus.SERVICE_UNAVAILABLE,
        new ErrorResponse(
            "KI-Dienst vorübergehend nicht verfügbar",
            HttpStatus.SERVICE_UNAVAILABLE.value(),
            Instant.now()));
  }

  @ExceptionHandler(NonTransientAiException.class)
  public ResponseEntity<ErrorResponse> handleNonTransientAiException(NonTransientAiException ex) {
    log.error("Non-transient AI service error: {}", errorSanitizer.sanitize(ex.getMessage()));
    return respond(
        HttpStatus.BAD_GATEWAY,
        new ErrorResponse("Fehler im KI-Dienst", HttpStatus.BAD_GATEWAY.value(), Instant.now()));
  }

  /**
   * #768: since #766 moved chat/query calls onto {@code OpenAiChatModel}'s OpenAI-Java-SDK-based
   * implementation (Spring AI 2.0), a connection failure (host unreachable, DNS failure, timeout)
   * no longer throws {@link TransientAiException} - it throws {@link OpenAIIoException}, a plain
   * {@code RuntimeException} neither {@link TransientAiException} nor {@link
   * NonTransientAiException} extends (see {@code ActiveChatModelResolverIntegrationTest}, which
   * documented this as a follow-up rather than fixing it as part of #758). {@link
   * OpenAIRetryableException} is the SDK's own explicit "this is safe to retry" signal (its
   * Javadoc: thrown for an error the SDK's built-in retry already exhausted) - both are as
   * transient as the connection-level failures {@link #handleTransientAiException} already covers,
   * and get the exact same {@code 503} and message rather than a second, differently worded one for
   * what is the same situation from a caller's point of view.
   */
  @ExceptionHandler({OpenAIIoException.class, OpenAIRetryableException.class})
  public ResponseEntity<ErrorResponse> handleOpenAiTransientException(RuntimeException ex) {
    log.warn("Transient AI service error: {}", errorSanitizer.sanitize(ex.getMessage()));
    return respond(
        HttpStatus.SERVICE_UNAVAILABLE,
        new ErrorResponse(
            "KI-Dienst vorübergehend nicht verfügbar",
            HttpStatus.SERVICE_UNAVAILABLE.value(),
            Instant.now()));
  }

  /**
   * #768: the SDK's own {@link OpenAIServiceException} hierarchy (thrown once the provider actually
   * answered with a non-2xx status - {@code BadRequestException}, {@code UnauthorizedException},
   * {@code NotFoundException}, {@code InternalServerException}, {@code RateLimitException}, etc.)
   * carries {@link OpenAIServiceException#statusCode()}, which is what distinguishes a transient
   * provider-side problem from a permanent one: {@code 429} (rate limited) and {@code 5xx} (the
   * provider's own server error) are retryable in the same sense {@link
   * #handleTransientAiException} already is, while every other status - most notably {@code
   * 401}/{@code 403} (misconfigured credentials) and {@code 404} (unknown model identifier) -
   * reflects a request that will keep failing the same way until an operator fixes the
   * configuration, mapped like {@link #handleNonTransientAiException} already maps Spring AI's own
   * {@link NonTransientAiException}. The distinct branches intentionally reuse those two handlers'
   * exact status codes and messages rather than introducing new ones for what are, from a caller's
   * perspective, the same two situations.
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
      // #768 review, should-finding 5: RateLimitException (429) is the one OpenAIServiceException
      // subtype whose headers() routinely carries a Retry-After the provider actually computed -
      // worth passing through to the caller rather than leaving them to guess a backoff.
      if (ex instanceof RateLimitException rateLimitException) {
        List<String> retryAfter = rateLimitException.headers().values(HttpHeaders.RETRY_AFTER);
        if (!retryAfter.isEmpty()) {
          responseBuilder =
              responseBuilder.header(HttpHeaders.RETRY_AFTER, retryAfter.toArray(new String[0]));
        }
      }
      return respond(
          responseBuilder,
          new ErrorResponse(
              "KI-Dienst vorübergehend nicht verfügbar",
              HttpStatus.SERVICE_UNAVAILABLE.value(),
              Instant.now()));
    }
    log.error(
        "Non-transient AI service error ({}): {}",
        statusCode,
        errorSanitizer.sanitize(ex.getMessage()));
    return respond(
        HttpStatus.BAD_GATEWAY,
        new ErrorResponse("Fehler im KI-Dienst", HttpStatus.BAD_GATEWAY.value(), Instant.now()));
  }

  /**
   * #768 review, should-finding 1: the remaining direct {@link OpenAIException} subtypes - {@code
   * OpenAIInvalidDataException} chief among them - are neither a connection-level failure ({@link
   * #handleOpenAiTransientException}) nor a genuine HTTP error response from the provider ({@link
   * #handleOpenAiServiceException}); the SDK throws this one when a response it received does not
   * match the shape it expects, which is exactly what an only OpenAI-*compatible* server (Ollama,
   * this project's own docker-compose default) can produce for a request its more limited
   * implementation does not fully support. Without this handler, that fell through to {@link
   * #handleGenericException}'s generic {@code 500}. Mapped to the same {@code 502} {@link
   * #handleNonTransientAiException} and {@link #handleOpenAiServiceException}'s non-transient
   * branch already use for "the provider answered, but not usefully" - Spring dispatches to the
   * most specific matching {@code @ExceptionHandler} by exception-hierarchy distance regardless of
   * declaration order, so {@link #handleOpenAiTransientException} and {@link
   * #handleOpenAiServiceException} remain authoritative for their own, more specific types.
   */
  @ExceptionHandler(OpenAIException.class)
  public ResponseEntity<ErrorResponse> handleOpenAiException(OpenAIException ex) {
    log.error("Unexpected AI service error: {}", errorSanitizer.sanitize(ex.getMessage()));
    return respond(
        HttpStatus.BAD_GATEWAY,
        new ErrorResponse("Fehler im KI-Dienst", HttpStatus.BAD_GATEWAY.value(), Instant.now()));
  }

  /**
   * #456: a request to a path no controller serves reaches Spring's static resource handling as the
   * last resort, which throws {@link NoResourceFoundException} (or, with a differently configured
   * resource handler, {@link NoHandlerFoundException}) - a caller error, not a server error.
   * Without this handler, it fell through to {@link #handleGenericException}'s {@code 500} and
   * logged a full stacktrace on {@code ERROR}, which both hid the actual cause from API callers (an
   * unmapped path looks identical to a real server error) and drowned the log in noise from
   * scanners probing paths like {@code /wp-admin} or {@code /.env}. Logged at {@code DEBUG} without
   * a stacktrace, since the request path itself is all there is to know.
   */
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<ErrorResponse> handleNoResourceFoundException(Exception ex) {
    log.debug("No handler found for request: {}", errorSanitizer.sanitize(ex.getMessage()));
    return respond(
        HttpStatus.NOT_FOUND,
        new ErrorResponse(
            "Die angeforderte Ressource wurde nicht gefunden",
            HttpStatus.NOT_FOUND.value(),
            Instant.now()));
  }

  /**
   * #1707: both exceptions arise before or instead of a controller method - the method mismatch in
   * handler mapping, the media type while reading the body - and Spring's own {@code
   * DefaultHandlerExceptionResolver} renders them as 405 and 415 on its own. It never sees them,
   * because {@code ExceptionHandlerExceptionResolver} is consulted first and {@link
   * #handleGenericException} claims every {@link Exception}, so both arrived as {@code 500} with an
   * {@code ERROR} stacktrace. Logged at {@code DEBUG} without a stacktrace like {@link
   * #handleNoResourceFoundException}: a caller without a session can raise either at will.
   *
   * <p>The {@code Allow} header is part of the contract, not a nicety - RFC 9110, section 15.5.6
   * requires a 405 to name the methods the resource does support, and taking the response away from
   * {@code DefaultHandlerExceptionResolver} means taking that header with it.
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
        response,
        new ErrorResponse(
            "Die HTTP-Methode wird für diese Ressource nicht unterstützt",
            HttpStatus.METHOD_NOT_ALLOWED.value(),
            Instant.now()));
  }

  /** The media-type half of {@link #handleHttpRequestMethodNotSupportedException} (#1707). */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleHttpMediaTypeNotSupportedException(
      HttpMediaTypeNotSupportedException ex) {
    log.debug("Unsupported media type: {}", errorSanitizer.sanitize(ex.getMessage()));
    return respond(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        new ErrorResponse(
            "Der Inhaltstyp der Anfrage wird nicht unterstützt",
            HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
            Instant.now()));
  }

  /**
   * The {@code Accept} sibling of the two above (#1707). Its status code is <em>not</em> what was
   * wrong: a caller who accepts nothing this API writes cannot be sent a body at all, so the answer
   * is a bodyless 406 either way - what {@link #handleGenericException} added was a stacktrace per
   * request, raisable without a session by anyone sending {@code Accept: application/xml}.
   *
   * <p><b>The answer carries no body, and that is load-bearing rather than a simplification.</b>
   * Returning an {@link ErrorResponse} here would be unwritable for the very reason the exception
   * was raised; {@code AbstractMessageConverterMethodProcessor} would throw a second {@code
   * HttpMediaTypeNotAcceptableException}, and {@code ExceptionHandlerExceptionResolver} logs any
   * exception other than the original one via {@code logger.warn(msg, throwable)} - so the
   * stacktrace this branch exists to remove would come back from a Spring logger instead. {@code
   * GlobalExceptionHandlerNotAcceptableTest} therefore taps the ROOT logger, not this class's.
   */
  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<ErrorResponse> handleHttpMediaTypeNotAcceptableException(
      HttpMediaTypeNotAcceptableException ex) {
    log.debug("Unacceptable response format: {}", errorSanitizer.sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
  }

  @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDeniedException(
      org.springframework.security.access.AccessDeniedException ex) {
    return respond(
        HttpStatus.FORBIDDEN,
        new ErrorResponse("Zugriff verweigert", HttpStatus.FORBIDDEN.value(), Instant.now()));
  }

  /**
   * Maps the domain exception hierarchy in {@code io.opaa.common} to the identical response body
   * {@link #handleResponseStatusException} produces for {@link ResponseStatusException} - status
   * and {@link Exception#getMessage()} as the reason (#875).
   */
  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<ErrorResponse> handleNotFoundException(NotFoundException ex) {
    return respond(
        HttpStatus.NOT_FOUND,
        new ErrorResponse(ex.getMessage(), HttpStatus.NOT_FOUND.value(), Instant.now()));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleDomainAccessDeniedException(AccessDeniedException ex) {
    return respond(
        HttpStatus.FORBIDDEN,
        new ErrorResponse(ex.getMessage(), HttpStatus.FORBIDDEN.value(), Instant.now()));
  }

  /** A conflict may carry a stable {@code code} the client acts on (ADR-0033, Entscheidung 4). */
  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<ErrorResponse> handleConflictException(ConflictException ex) {
    ErrorResponse body =
        new ErrorResponse(ex.getMessage(), HttpStatus.CONFLICT.value(), Instant.now());
    if (ex.getCode() != null) {
      body.setCode(ex.getCode());
    }
    return respond(HttpStatus.CONFLICT, body);
  }

  /** A refusal may carry a stable {@code code} the client acts on (e.g. {@code TOKEN_INVALID}). */
  @ExceptionHandler(ValidationException.class)
  public ResponseEntity<ErrorResponse> handleValidationException(ValidationException ex) {
    ErrorResponse body =
        new ErrorResponse(ex.getMessage(), HttpStatus.BAD_REQUEST.value(), Instant.now());
    if (ex.getCode() != null) {
      body.setCode(ex.getCode());
    }
    return respond(HttpStatus.BAD_REQUEST, body);
  }

  /**
   * The field-level sibling of {@link #handleValidationException(ValidationException)}: the same
   * {@code 400} envelope, plus {@code fieldErrors} - one entry per field and violated rule with a
   * stable code - so a form can mark the fields instead of showing one sentence.
   */
  @ExceptionHandler(FieldValidationException.class)
  public ResponseEntity<ErrorResponse> handleFieldValidationException(FieldValidationException ex) {
    ErrorResponse body =
        new ErrorResponse(ex.getMessage(), HttpStatus.BAD_REQUEST.value(), Instant.now());
    body.setFieldErrors(
        ex.fieldErrors().stream()
            .map(error -> new FieldError(error.field(), error.code(), error.message()))
            .toList());
    return respond(HttpStatus.BAD_REQUEST, body);
  }

  @ExceptionHandler(UnauthorizedException.class)
  public ResponseEntity<ErrorResponse> handleUnauthorizedException(UnauthorizedException ex) {
    return respond(
        HttpStatus.UNAUTHORIZED,
        new ErrorResponse(ex.getMessage(), HttpStatus.UNAUTHORIZED.value(), Instant.now()));
  }

  @ExceptionHandler(PayloadTooLargeException.class)
  public ResponseEntity<ErrorResponse> handlePayloadTooLargeException(PayloadTooLargeException ex) {
    return respond(
        HttpStatus.PAYLOAD_TOO_LARGE,
        new ErrorResponse(ex.getMessage(), HttpStatus.PAYLOAD_TOO_LARGE.value(), Instant.now()));
  }

  @ExceptionHandler(TooManyRequestsException.class)
  public ResponseEntity<ErrorResponse> handleTooManyRequestsException(TooManyRequestsException ex) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);
    if (ex.retryAfterSeconds() > 0) {
      response.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
    }
    return respond(
        response,
        new ErrorResponse(ex.getMessage(), HttpStatus.TOO_MANY_REQUESTS.value(), Instant.now()));
  }

  @ExceptionHandler(ServiceUnavailableException.class)
  public ResponseEntity<ErrorResponse> handleServiceUnavailableException(
      ServiceUnavailableException ex) {
    log.error("Server error raised by application", ex);
    return respond(
        HttpStatus.SERVICE_UNAVAILABLE,
        new ErrorResponse(ex.getMessage(), HttpStatus.SERVICE_UNAVAILABLE.value(), Instant.now()));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponse> handleResponseStatusException(ResponseStatusException ex) {
    HttpStatusCode status = ex.getStatusCode();
    if (status.is5xxServerError()) {
      log.error("Server error raised by application", ex);
    }
    String message = ex.getReason() != null ? ex.getReason() : defaultMessageFor(status);
    return respond(status, new ErrorResponse(message, status.value(), Instant.now()));
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
        "Die Datei ist zu groß. Erlaubt sind höchstens "
            + (uploadProperties.maxFileSize() / (1024 * 1024))
            + " MB.";
    return respond(
        HttpStatus.PAYLOAD_TOO_LARGE,
        new ErrorResponse(message, HttpStatus.PAYLOAD_TOO_LARGE.value(), Instant.now()));
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
    return respond(
        HttpStatus.BAD_REQUEST,
        new ErrorResponse(message, HttpStatus.BAD_REQUEST.value(), Instant.now()));
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
    return respond(
        HttpStatus.BAD_REQUEST,
        new ErrorResponse(
            "Die hochgeladene Datei konnte nicht verarbeitet werden",
            HttpStatus.BAD_REQUEST.value(),
            Instant.now()));
  }

  /**
   * #483: raised by {@code CredentialsEncryptor} when {@code OPAA_CREDENTIALS_ENCRYPTION_KEY} is
   * missing or invalid at the moment a library's {@code sourceCredentials} would actually be
   * encrypted or decrypted. Mapped to a distinct {@code 503} - not the {@link
   * #handleGenericException} {@code 500} fallback - so an operator sees "set the key" rather than
   * an opaque server error. The exception can surface either directly from {@code
   * KnowledgeLibraryService} (thrown while the JPA flush that runs the {@code
   * SourceCredentialsConverter} is still on this request's call stack) or wrapped by JPA/Spring's
   * exception translation, which is why {@link #handleGenericException} also unwraps for it via
   * {@link #findCause}.
   */
  @ExceptionHandler(CredentialsEncryptionKeyMissingException.class)
  public ResponseEntity<ErrorResponse> handleCredentialsEncryptionKeyMissingException(
      CredentialsEncryptionKeyMissingException ex) {
    log.error("Credentials encryption key missing or invalid", ex);
    return respond(
        HttpStatus.SERVICE_UNAVAILABLE,
        new ErrorResponse(ex.getMessage(), HttpStatus.SERVICE_UNAVAILABLE.value(), Instant.now()));
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
    return respond(status, new ErrorResponse(message, status.value(), Instant.now()));
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
    CredentialsEncryptionKeyMissingException credentialsCause =
        findCause(ex, CredentialsEncryptionKeyMissingException.class);
    if (credentialsCause != null) {
      return handleCredentialsEncryptionKeyMissingException(credentialsCause);
    }
    // #768 review, optional finding 4: a com.openai.errors.* exception wrapped by something else
    // (e.g. Spring AI's own retry/advisor machinery) would otherwise still land here uncaught by
    // any of the dedicated handlers above, which only match against the exception actually thrown
    // to this method - not its cause chain.
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
    return respond(
        HttpStatus.INTERNAL_SERVER_ERROR,
        new ErrorResponse(
            "Interner Serverfehler", HttpStatus.INTERNAL_SERVER_ERROR.value(), Instant.now()));
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
