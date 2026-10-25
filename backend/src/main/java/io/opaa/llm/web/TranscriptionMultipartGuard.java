package io.opaa.llm.web;

import io.opaa.api.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a multipart request to the dictation endpoint away with {@code 415} before anything parses
 * it. The servlet container writes multipart parts above its file-size threshold to a temporary
 * file as soon as a filter or the dispatcher asks for them, so this check runs first in the chain,
 * ahead of security and parameter parsing; it reads nothing but the {@code Content-Type} header.
 */
class TranscriptionMultipartGuard extends OncePerRequestFilter {

  static final String MESSAGE = "Der Inhaltstyp der Anfrage wird nicht unterstützt";

  private final ObjectMapper objectMapper;
  private final Clock clock;

  TranscriptionMultipartGuard(ObjectMapper objectMapper, Clock clock) {
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String contentType = request.getContentType();
    return contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    HttpStatus status = HttpStatus.UNSUPPORTED_MEDIA_TYPE;
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(
        response.getOutputStream(), new ErrorResponse(MESSAGE, status.value(), Instant.now(clock)));
  }
}
