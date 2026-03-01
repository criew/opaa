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
    assertEquals(500, body.status());
    assertEquals("Internal server error", body.error());
    assertNotNull(body.timestamp());
  }

  @Test
  void handleTransientAiExceptionReturnsServiceUnavailable() {
    var response = handler.handleTransientAiException(new TransientAiException("AI timeout"));
    assertEquals(503, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("AI service temporarily unavailable", body.error());
  }

  @Test
  void handleNonTransientAiExceptionReturnsBadGateway() {
    var response =
        handler.handleNonTransientAiException(new NonTransientAiException("Invalid model"));
    assertEquals(502, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("AI service error", body.error());
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
    assertEquals("AI service error", body.error());
  }

  @Test
  void handleIllegalArgumentExceptionReturnsBadRequest() {
    var response =
        handler.handleIllegalArgumentException(new IllegalArgumentException("bad input"));
    assertEquals(400, response.getStatusCode().value());
    ErrorResponse body = response.getBody();
    assertNotNull(body);
    assertEquals("bad input", body.error());
  }
}
