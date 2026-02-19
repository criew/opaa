package io.opaa.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.opaa.api.dto.ErrorResponse;
import org.junit.jupiter.api.Test;

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
}
