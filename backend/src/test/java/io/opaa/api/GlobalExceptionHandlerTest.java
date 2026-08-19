package io.opaa.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.opaa.api.dto.ErrorResponse;
import io.opaa.library.UploadProperties;
import io.opaa.security.CredentialsEncryptionKeyMissingException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler =
      new GlobalExceptionHandler(new UploadProperties(null, 52_428_800L));

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
  void handleIllegalArgumentExceptionReturnsBadRequest() {
    var response =
        handler.handleIllegalArgumentException(new IllegalArgumentException("bad input"));
    assertEquals(400, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("bad input", body.getError());
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
    assertEquals("Die Datei ist zu gross. Erlaubt sind hoechstens 50 MB.", body.getError());
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
    assertEquals("Die Datei ist zu gross. Erlaubt sind hoechstens 50 MB.", body.getError());
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
