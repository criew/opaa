package io.opaa.auth.local;

import io.opaa.api.dto.ErrorResponse;
import io.opaa.api.types.PasswordChangeReason;
import io.opaa.auth.LocalIssuer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Forces the password change (ADR-0033, Entscheidung 8): a local access token with {@code pcr =
 * true} reaches nothing but {@code /api/v1/auth/local/} - every other request is answered {@code
 * 403} with code {@value #CODE} and the account's {@code password_change_reason}, which the SPA
 * shows as a plain sentence. Runs after {@code AuthorizationFilter}, so it sees the authenticated
 * token; the flag is read from the token (no lookup on the hot path) and stays correct because
 * every act that flips it also invalidates the account's tokens. The reason is read from the
 * database only on the refusal path.
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

  public static final String CODE = "PASSWORD_CHANGE_REQUIRED";
  static final String EXEMPT_PREFIX = "/api/v1/auth/local/";
  static final String MESSAGE = "Bitte legen Sie zuerst ein neues Passwort fest.";

  private final LocalCredentialsRepository credentials;
  private final JsonMapper jsonMapper;

  public PasswordChangeRequiredFilter(
      LocalCredentialsRepository credentials, JsonMapper jsonMapper) {
    this.credentials = credentials;
    this.jsonMapper = jsonMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken token
        && requiresPasswordChange(token.getToken())
        && !request.getRequestURI().startsWith(EXEMPT_PREFIX)) {
      refuse(response, token.getToken());
      return;
    }
    filterChain.doFilter(request, response);
  }

  private static boolean requiresPasswordChange(Jwt jwt) {
    return LocalIssuer.URN.equals(jwt.getClaimAsString("iss"))
        && Boolean.TRUE.equals(jwt.getClaimAsBoolean(LocalAccessTokenService.PCR_CLAIM));
  }

  private void refuse(HttpServletResponse response, Jwt jwt) throws IOException {
    ErrorResponse body = new ErrorResponse(MESSAGE, HttpStatus.FORBIDDEN.value(), Instant.now());
    body.setCode(CODE);
    reasonOf(jwt).ifPresent(reason -> body.setReason(reason.name()));
    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    jsonMapper.writeValue(response.getOutputStream(), body);
  }

  private java.util.Optional<PasswordChangeReason> reasonOf(Jwt jwt) {
    try {
      return credentials
          .findById(UUID.fromString(jwt.getSubject()))
          .map(LocalCredentials::getPasswordChangeReason);
    } catch (IllegalArgumentException notAUuid) {
      return java.util.Optional.empty();
    }
  }
}
