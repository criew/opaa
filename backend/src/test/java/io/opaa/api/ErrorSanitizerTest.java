package io.opaa.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ErrorSanitizerTest {

  private final ErrorSanitizer sanitizer = new ErrorSanitizer();

  @Test
  void sanitizeReturnsNullForNullInput() {
    assertNull(sanitizer.sanitize(null));
  }

  @Test
  void sanitizeRedactsOpenAiApiKey() {
    String message =
        "Failed to connect with key sk-abcdefghijklmnopqrstuvwxyz1234567890ABCDEFGHIJKLMNO";
    String result = sanitizer.sanitize(message);
    assertEquals("Failed to connect with key [REDACTED]", result);
  }

  @Test
  void sanitizeRedactsApiKeyParameter() {
    String message = "Error: api_key=my-secret-key-123 was invalid";
    String result = sanitizer.sanitize(message);
    assertEquals("Error: [REDACTED] was invalid", result);
  }

  @Test
  void sanitizeRedactsUrlQueryParameters() {
    String message =
        "Failed to connect to https://api.openai.com/v1/chat/completions?api_key=sk-secret123";
    String result = sanitizer.sanitize(message);
    assertEquals(
        "Failed to connect to https://api.openai.com/v1/chat/completions?[REDACTED]", result);
  }

  @Test
  void sanitizeRedactsUnixFilePaths() {
    String message =
        "Document processing failed: /opt/app/data/sensitive/financial/2023/report.pdf";
    String result = sanitizer.sanitize(message);
    assertEquals("Document processing failed: [PATH]", result);
  }

  @Test
  void sanitizeRedactsWindowsFilePaths() {
    String message = "File not found: C:\\Users\\admin\\Documents\\secret.docx";
    String result = sanitizer.sanitize(message);
    assertEquals("File not found: [PATH]", result);
  }

  @Test
  void sanitizePreservesSimpleMessages() {
    String message = "Connection timeout after 30 seconds";
    assertEquals(message, sanitizer.sanitize(message));
  }

  @Test
  void sanitizeHandlesMultipleSensitivePatterns() {
    String message =
        "Error with key sk-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa at /opt/app/config";
    String result = sanitizer.sanitize(message);
    assertEquals("Error with key [REDACTED] at [PATH]", result);
  }

  @Test
  void sanitizePreservesUrlWithoutParameters() {
    String message = "Failed to reach https://api.openai.com/v1/models";
    String result = sanitizer.sanitize(message);
    assertEquals("Failed to reach https://api.openai.com/v1/models", result);
  }
}
