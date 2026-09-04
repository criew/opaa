package io.opaa.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.openai.core.http.Headers;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import io.opaa.api.dto.ErrorResponse;
import io.opaa.library.UploadProperties;
import io.opaa.security.CredentialsEncryptionKeyMissingException;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler =
      new GlobalExceptionHandler(new UploadProperties(null, 52_428_800L, null, 0, 0));

  @Test
  void handleGenericExceptionReturnsInternalServerError() {
    var response = handler.handleGenericException(new RuntimeException("test error"));
    assertEquals(500, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals(500, body.getStatus());
    assertEquals("Interner Serverfehler", body.getError());
    assertNotNull(body.getTimestamp());
  }

  @Test
  void handleTransientAiExceptionReturnsServiceUnavailable() {
    var response = handler.handleTransientAiException(new TransientAiException("AI timeout"));
    assertEquals(503, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("KI-Dienst vorübergehend nicht verfügbar", body.getError());
  }

  @Test
  void handleNonTransientAiExceptionReturnsBadGateway() {
    var response =
        handler.handleNonTransientAiException(new NonTransientAiException("Invalid model"));
    assertEquals(502, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("Fehler im KI-Dienst", body.getError());
  }

  @Test
  void handleNonTransientAiExceptionSanitizesLoggedMessage() {
    var response =
        handler.handleNonTransientAiException(
            new NonTransientAiException(
                "Error with sk-abcdefghijklmnopqrstuvwxyz1234567890ABCDEFGHIJKLMNO"));
    assertEquals(502, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("Fehler im KI-Dienst", body.getError());
  }

  @Test
  void handleNoResourceFoundExceptionReturnsNotFound() {
    // #456: an unmapped path fell through to handleGenericException and answered 500 instead of
    // the expected 404 - see GlobalExceptionHandlerUnmappedPathTest for the full-stack
    // reproduction against an actual unmapped route.
    var response =
        handler.handleNoResourceFoundException(
            new NoResourceFoundException(
                HttpMethod.GET, "gibtesnicht", "No static resource gibtesnicht."));
    assertEquals(404, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals(404, body.getStatus());
    assertEquals("Die angeforderte Ressource wurde nicht gefunden", body.getError());
  }

  @Test
  void handleNoResourceFoundExceptionAlsoHandlesNoHandlerFoundException() {
    var response =
        handler.handleNoResourceFoundException(
            new NoHandlerFoundException("GET", "/gibtesnicht", null));
    assertEquals(404, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("Die angeforderte Ressource wurde nicht gefunden", body.getError());
  }

  @Test
  void handleOpenAiTransientExceptionMapsOpenAiIoExceptionToServiceUnavailable() {
    // #768: since #766, a connection failure against the SDK-based OpenAiChatModel throws
    // com.openai.errors.OpenAIIoException, not TransientAiException - previously uncaught here, it
    // fell through to handleGenericException's 500 (see
    // ActiveChatModelResolverIntegrationTest#anUnreachableActiveModelFailsWithoutFallingBackToAnotherModel,
    // which documented this as a follow-up when #758 introduced ActiveChatModelResolver).
    var response =
        handler.handleOpenAiTransientException(new OpenAIIoException("connection refused"));
    assertEquals(503, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("KI-Dienst vorübergehend nicht verfügbar", body.getError());
  }

  @Test
  void handleOpenAiTransientExceptionMapsOpenAiRetryableExceptionToServiceUnavailable() {
    var response =
        handler.handleOpenAiTransientException(new OpenAIRetryableException("retries exhausted"));
    assertEquals(503, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("KI-Dienst vorübergehend nicht verfügbar", body.getError());
  }

  @Test
  void handleOpenAiServiceExceptionMapsRateLimitToServiceUnavailable() {
    var response =
        handler.handleOpenAiServiceException(
            RateLimitException.builder().headers(Headers.builder().build()).build());
    assertEquals(503, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("KI-Dienst vorübergehend nicht verfügbar", body.getError());
  }

  @Test
  void handleOpenAiServiceExceptionMapsProviderServerErrorToServiceUnavailable() {
    var response =
        handler.handleOpenAiServiceException(
            InternalServerException.builder()
                .statusCode(500)
                .headers(Headers.builder().build())
                .build());
    assertEquals(503, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("KI-Dienst vorübergehend nicht verfügbar", body.getError());
  }

  @Test
  void handleOpenAiServiceExceptionMapsUnauthorizedToBadGateway() {
    var response =
        handler.handleOpenAiServiceException(
            UnauthorizedException.builder().headers(Headers.builder().build()).build());
    assertEquals(502, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("Fehler im KI-Dienst", body.getError());
  }

  @Test
  void handleOpenAiServiceExceptionMapsNotFoundToBadGateway() {
    var response =
        handler.handleOpenAiServiceException(
            NotFoundException.builder().headers(Headers.builder().build()).build());
    assertEquals(502, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("Fehler im KI-Dienst", body.getError());
  }

  @Test
  void handleOpenAiServiceExceptionMapsRateLimitAndForwardsRetryAfterHeader() {
    // #768 review, should-finding 5: RateLimitException's headers() routinely carries a
    // Retry-After the provider actually computed - worth passing through rather than leaving the
    // caller to guess a backoff.
    var response =
        handler.handleOpenAiServiceException(
            RateLimitException.builder()
                .headers(Headers.builder().put(HttpHeaders.RETRY_AFTER, "30").build())
                .build());
    assertEquals(503, response.getStatusCode().value());
    assertEquals(List.of("30"), response.getHeaders().get(HttpHeaders.RETRY_AFTER));
  }

  @Test
  void handleOpenAiServiceExceptionWithoutRetryAfterHeaderOmitsIt() {
    var response =
        handler.handleOpenAiServiceException(
            RateLimitException.builder().headers(Headers.builder().build()).build());
    assertEquals(503, response.getStatusCode().value());
    assertEquals(null, response.getHeaders().get(HttpHeaders.RETRY_AFTER));
  }

  @Test
  void handleOpenAiExceptionMapsRemainingSubtypesToBadGateway() {
    // #768 review, should-finding 1: OpenAIInvalidDataException is neither a connection-level
    // failure (handleOpenAiTransientException) nor a genuine HTTP error response
    // (handleOpenAiServiceException) - it is thrown when an only OpenAI-*compatible* server
    // (Ollama, this project's own docker-compose default) answers in a shape the SDK does not
    // expect. Without this handler it fell through to handleGenericException's 500.
    var response =
        handler.handleOpenAiException(new OpenAIInvalidDataException("unexpected response shape"));
    assertEquals(502, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("Fehler im KI-Dienst", body.getError());
  }

  @Test
  void handleGenericExceptionUnwrapsAWrappedOpenAiServiceException() {
    // #768 review, optional finding 4: a com.openai.errors.* exception wrapped by something else
    // (e.g. Spring AI's own retry/advisor machinery) must still be recognized via the cause chain,
    // exactly like the pre-existing CredentialsEncryptionKeyMissingException unwrapping below.
    var cause = UnauthorizedException.builder().headers(Headers.builder().build()).build();
    var response = handler.handleGenericException(new RuntimeException("wrapped", cause));
    assertEquals(502, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("Fehler im KI-Dienst", body.getError());
  }

  @Test
  void handleGenericExceptionUnwrapsAWrappedOpenAiTransientException() {
    var cause = new OpenAIIoException("connection refused");
    var response = handler.handleGenericException(new RuntimeException("wrapped", cause));
    assertEquals(503, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("KI-Dienst vorübergehend nicht verfügbar", body.getError());
  }

  @Test
  void handleGenericExceptionUnwrapsAWrappedPlainOpenAiException() {
    OpenAIException cause = new OpenAIInvalidDataException("unexpected response shape");
    var response = handler.handleGenericException(new RuntimeException("wrapped", cause));
    assertEquals(502, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("Fehler im KI-Dienst", body.getError());
  }

  @Test
  void handleIllegalArgumentExceptionReturnsBadRequest() {
    var response =
        handler.handleIllegalArgumentException(new IllegalArgumentException("bad input"));
    assertEquals(400, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("bad input", body.getError());
  }

  /**
   * #875: each domain exception in {@code io.opaa.common} must produce the identical response body
   * {@link #handleResponseStatusExceptionKeepsStatusAndReason} pins for {@link
   * ResponseStatusException} - status and the exception's own message as the reason.
   */
  @Test
  void handleNotFoundExceptionReturnsSameBodyShapeAsResponseStatusException() {
    var response =
        handler.handleNotFoundException(
            new io.opaa.common.NotFoundException("Space nicht gefunden"));
    assertEquals(404, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals(404, body.getStatus());
    assertEquals("Space nicht gefunden", body.getError());
    assertNotNull(body.getTimestamp());
  }

  @Test
  void handleDomainAccessDeniedExceptionReturnsSameBodyShapeAsResponseStatusException() {
    var response =
        handler.handleDomainAccessDeniedException(
            new io.opaa.common.AccessDeniedException("Sie sind kein Mitglied dieses Space"));
    assertEquals(403, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals(403, body.getStatus());
    assertEquals("Sie sind kein Mitglied dieses Space", body.getError());
  }

  @Test
  void handleConflictExceptionReturnsSameBodyShapeAsResponseStatusException() {
    var response =
        handler.handleConflictException(
            new io.opaa.common.ConflictException("Der Benutzer ist bereits Mitglied dieses Space"));
    assertEquals(409, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals(409, body.getStatus());
    assertEquals("Der Benutzer ist bereits Mitglied dieses Space", body.getError());
  }

  @Test
  void handleDomainValidationExceptionReturnsSameBodyShapeAsResponseStatusException() {
    var response =
        handler.handleValidationException(
            new io.opaa.common.ValidationException("name ist erforderlich"));
    assertEquals(400, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals(400, body.getStatus());
    assertEquals("name ist erforderlich", body.getError());
  }

  @Test
  void handleUnauthorizedExceptionReturnsSameBodyShapeAsResponseStatusException() {
    var response =
        handler.handleUnauthorizedException(
            new io.opaa.common.UnauthorizedException("Benutzer nicht gefunden"));
    assertEquals(401, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals(401, body.getStatus());
    assertEquals("Benutzer nicht gefunden", body.getError());
  }

  @Test
  void handlePayloadTooLargeExceptionReturnsSameBodyShapeAsResponseStatusException() {
    var response =
        handler.handlePayloadTooLargeException(
            new io.opaa.common.PayloadTooLargeException(
                "Das Logo darf höchstens 512 KiB groß sein"));
    assertEquals(413, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals(413, body.getStatus());
    assertEquals("Das Logo darf höchstens 512 KiB groß sein", body.getError());
  }

  @Test
  void handleTooManyRequestsExceptionReturnsSameBodyShapeAsResponseStatusException() {
    var response =
        handler.handleTooManyRequestsException(
            new io.opaa.common.TooManyRequestsException(
                "Es werden gerade zu viele Anhänge geöffnet."
                    + " Bitte versuchen Sie es in einem Moment erneut."));
    assertEquals(429, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals(429, body.getStatus());
    assertEquals(
        "Es werden gerade zu viele Anhänge geöffnet."
            + " Bitte versuchen Sie es in einem Moment erneut.",
        body.getError());
    assertNotNull(body.getTimestamp());
  }

  @Test
  void handleServiceUnavailableExceptionReturnsSameBodyShapeAsResponseStatusException() {
    var response =
        handler.handleServiceUnavailableException(
            new io.opaa.common.ServiceUnavailableException(
                "Indizierung derzeit nicht möglich, bitte später erneut versuchen"));
    assertEquals(503, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals(503, body.getStatus());
    assertEquals(
        "Indizierung derzeit nicht möglich, bitte später erneut versuchen", body.getError());
  }

  @Test
  void handleResponseStatusExceptionKeepsStatusAndReason() {
    var response =
        handler.handleResponseStatusException(
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Space nicht gefunden"));
    assertEquals(404, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals(404, body.getStatus());
    assertEquals("Space nicht gefunden", body.getError());
    assertNotNull(body.getTimestamp());
  }

  @Test
  void handleResponseStatusExceptionWithoutReasonUsesFallbackMessage() {
    var response =
        handler.handleResponseStatusException(new ResponseStatusException(HttpStatus.CONFLICT));
    assertEquals(409, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("Die Anfrage konnte nicht verarbeitet werden", body.getError());
  }

  @Test
  void handleResponseStatusExceptionWithServerErrorUsesGenericMessage() {
    var response =
        handler.handleResponseStatusException(
            new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR));
    assertEquals(500, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("Interner Serverfehler", body.getError());
  }

  @Test
  void handleDataIntegrityViolationExceptionReturnsConflictForForeignKeyViolation() {
    var response =
        handler.handleDataIntegrityViolationException(
            dataIntegrityViolation(
                "23503",
                "update or delete on table \"groups\" violates foreign key constraint"
                    + " \"fk_library_grant_group\""));
    assertEquals(409, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals(409, body.getStatus());
    assertEquals(
        "Der Datensatz wird noch verwendet und kann nicht geändert oder gelöscht werden",
        body.getError());
    assertFalse(body.getError().contains("fk_library_grant_group"));
  }

  @Test
  void handleDataIntegrityViolationExceptionReturnsConflictForUniqueViolation() {
    var response =
        handler.handleDataIntegrityViolationException(
            dataIntegrityViolation(
                "23505", "duplicate key value violates unique constraint \"uk_group_name\""));
    assertEquals(409, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("Ein Eintrag mit diesen Werten existiert bereits", body.getError());
    assertFalse(body.getError().contains("uk_group_name"));
  }

  @Test
  void handleDataIntegrityViolationExceptionReturnsBadRequestForNotNullViolation() {
    var response =
        handler.handleDataIntegrityViolationException(
            dataIntegrityViolation(
                "23502", "null value in column \"name\" violates not-null constraint"));
    assertEquals(400, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("Die übergebenen Daten sind unvollständig oder ungültig", body.getError());
  }

  @Test
  void handleDataIntegrityViolationExceptionWithoutSqlStateReturnsConflict() {
    var response =
        handler.handleDataIntegrityViolationException(
            new DataIntegrityViolationException("could not execute statement"));
    assertEquals(409, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals(
        "Die Aktion widerspricht bestehenden Daten und wurde nicht ausgeführt", body.getError());
  }

  @Test
  void handleMaxUploadSizeExceededExceptionReturnsPayloadTooLargeWithGermanMessage() {
    // #420 code review, finding 2: without a dedicated handler, this fell through to
    // handleGenericException and answered 500 instead of the acceptance criterion's 413.
    var response =
        handler.handleMaxUploadSizeExceededException(
            new MaxUploadSizeExceededException(52_428_800L));
    assertEquals(413, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals(413, body.getStatus());
    assertEquals("Die Datei ist zu groß. Erlaubt sind höchstens 50 MB.", body.getError());
  }

  @Test
  void handleMaxUploadSizeExceededExceptionReadsTheLimitFromUploadPropertiesNotTheException() {
    // #420 second code review round, nit 2: StandardServletMultipartResolver usually reports -1
    // as ex.getMaxUploadSize() - the handler must still name the actual configured limit, taken
    // from UploadProperties rather than the exception, which is why this passes -1 here and still
    // expects the figure in the message.
    var response =
        handler.handleMaxUploadSizeExceededException(new MaxUploadSizeExceededException(-1));
    assertEquals(413, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("Die Datei ist zu groß. Erlaubt sind höchstens 50 MB.", body.getError());
  }

  @Test
  void handleMissingServletRequestPartExceptionReturnsBadRequest() {
    var response =
        handler.handleMissingServletRequestPartException(
            new MissingServletRequestPartException("file"));
    assertEquals(400, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals(400, body.getStatus());
    assertEquals("Der Anfrageteil 'file' fehlt", body.getError());
  }

  @Test
  void handleMultipartExceptionReturnsBadRequest() {
    // #420 second code review round, nit 2: a malformed or aborted multipart body throws the
    // plain MultipartException, not the more specific MaxUploadSizeExceededException - without
    // this handler it fell through to handleGenericException's 500.
    var response = handler.handleMultipartException(new MultipartException("could not parse"));
    assertEquals(400, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("Die hochgeladene Datei konnte nicht verarbeitet werden", body.getError());
  }

  @Test
  void handleCredentialsEncryptionKeyMissingExceptionReturnsServiceUnavailable() {
    // #483: a missing/invalid OPAA_CREDENTIALS_ENCRYPTION_KEY must surface as a clear 503, not the
    // opaque 500 handleGenericException falls back to.
    var response =
        handler.handleCredentialsEncryptionKeyMissingException(
            new CredentialsEncryptionKeyMissingException(
                "OPAA_CREDENTIALS_ENCRYPTION_KEY ist nicht gesetzt"));
    assertEquals(503, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals(503, body.getStatus());
    assertEquals("OPAA_CREDENTIALS_ENCRYPTION_KEY ist nicht gesetzt", body.getError());
  }

  @Test
  void handleGenericExceptionUnwrapsAWrappedCredentialsEncryptionKeyMissingException() {
    // JPA/Spring exception translation can wrap the exception raised inside
    // SourceCredentialsConverter (during flush) before it reaches this handler - the fallback
    // Exception handler must still recognize it via its cause chain rather than reporting a bare
    // 500.
    var cause =
        new CredentialsEncryptionKeyMissingException(
            "OPAA_CREDENTIALS_ENCRYPTION_KEY ist nicht gesetzt");
    var response = handler.handleGenericException(new RuntimeException("wrapped", cause));
    assertEquals(503, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("OPAA_CREDENTIALS_ENCRYPTION_KEY ist nicht gesetzt", body.getError());
  }

  private DataIntegrityViolationException dataIntegrityViolation(String sqlState, String message) {
    return new DataIntegrityViolationException(
        "could not execute statement",
        new RuntimeException(message, new SQLException(message, sqlState)));
  }
}
