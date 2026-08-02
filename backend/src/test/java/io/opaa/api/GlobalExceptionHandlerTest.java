package io.opaa.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.opaa.api.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

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
}
