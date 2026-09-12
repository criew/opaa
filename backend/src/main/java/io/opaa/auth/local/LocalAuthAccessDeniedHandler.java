package io.opaa.auth.local;

import io.opaa.api.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code 403} of the {@code oidc} chain as the API's own error envelope: a refused CSRF double
 * submit on refresh or logout carries code {@value #CSRF_CODE}, so the SPA can tell it from the
 * {@code PASSWORD_CHANGE_REQUIRED} refusal and from a plain authorization failure, which keeps the
 * wording {@code GlobalExceptionHandler} uses for the same case.
 */
public class LocalAuthAccessDeniedHandler implements AccessDeniedHandler {

  public static final String CSRF_CODE = "CSRF_TOKEN_MISSING";
  static final String CSRF_MESSAGE =
      "Das CSRF-Token fehlt oder ist ungültig. Bitte laden Sie die Seite neu.";
  static final String DENIED_MESSAGE = "Zugriff verweigert";

  private final JsonMapper jsonMapper;

  public LocalAuthAccessDeniedHandler(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
      throws IOException {
    boolean csrf = denied instanceof CsrfException;
    ErrorResponse body =
        new ErrorResponse(
            csrf ? CSRF_MESSAGE : DENIED_MESSAGE, HttpStatus.FORBIDDEN.value(), Instant.now());
    if (csrf) {
      body.setCode(CSRF_CODE);
    }
    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    jsonMapper.writeValue(response.getOutputStream(), body);
  }
}
