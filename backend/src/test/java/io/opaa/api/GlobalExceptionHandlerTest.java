package io.opaa.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.opaa.api.dto.ErrorResponse;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

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

  private DataIntegrityViolationException dataIntegrityViolation(String sqlState, String message) {
    return new DataIntegrityViolationException(
        "could not execute statement",
        new RuntimeException(message, new SQLException(message, sqlState)));
  }
}
