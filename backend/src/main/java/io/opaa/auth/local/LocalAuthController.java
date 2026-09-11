package io.opaa.auth.local;

import io.opaa.api.dto.ErrorResponse;
import io.opaa.api.dto.LocalChangePasswordRequest;
import io.opaa.api.dto.LocalLoginRequest;
import io.opaa.api.dto.LocalTokenResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.auth.local.LocalAccessTokenService.IssuedAccessToken;
import io.opaa.auth.local.LocalLoginService.AuthenticatedLocalAccount;
import io.opaa.auth.local.LocalRefreshTokenService.IssuedRefreshToken;
import io.opaa.auth.local.LocalRefreshTokenService.RotationResult;
import io.opaa.common.ConflictException;
import io.opaa.common.UnauthorizedException;
import io.opaa.security.ClientIpResolver;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

/**
 * The endpoints of the local token issuer (ADR-0033, Entscheidungen 6-9): sign-in, rotation,
 * sign-out and the person's own password change under {@code /api/v1/auth/local}. Access tokens
 * travel in the body, the refresh token only as the HttpOnly cookie. Every refused sign-in is the
 * one {@link UnauthorizedException} with the one message; a refused refresh clears the cookie and
 * looks the same whether the token was unknown, expired or replayed. Exists in the {@code oidc}
 * profile only - the {@code dev} profile has no issuer.
 */
@RestController
@RequestMapping("/api/v1/auth/local")
@Profile("oidc")
public class LocalAuthController {

  static final String LOGIN_FAILED =
      "Anmeldung fehlgeschlagen. Bitte prüfen Sie E-Mail-Adresse und Passwort.";
  static final String SESSION_INVALID = "Die Sitzung ist abgelaufen oder ungültig.";

  private final LocalLoginService loginService;
  private final LocalAccessTokenService accessTokens;
  private final LocalRefreshTokenService refreshTokens;
  private final LocalTokenRevocationService revocation;
  private final LocalPasswordService passwords;
  private final LocalCredentialsRepository credentials;
  private final UserService userService;
  private final LocalRefreshCookies cookies;
  private final ClientIpResolver clientIpResolver;

  public LocalAuthController(
      LocalLoginService loginService,
      LocalAccessTokenService accessTokens,
      LocalRefreshTokenService refreshTokens,
      LocalTokenRevocationService revocation,
      LocalPasswordService passwords,
      LocalCredentialsRepository credentials,
      UserService userService,
      LocalRefreshCookies cookies,
      ClientIpResolver clientIpResolver) {
    this.loginService = loginService;
    this.accessTokens = accessTokens;
    this.refreshTokens = refreshTokens;
    this.revocation = revocation;
    this.passwords = passwords;
    this.credentials = credentials;
    this.userService = userService;
    this.cookies = cookies;
    this.clientIpResolver = clientIpResolver;
  }

  @PostMapping("/login")
  public ResponseEntity<LocalTokenResponse> login(
      @Valid @RequestBody LocalLoginRequest request, HttpServletRequest httpRequest) {
    AuthenticatedLocalAccount account =
        loginService
            .authenticate(
                request.getEmail(), request.getPassword(), clientIpResolver.resolve(httpRequest))
            .orElseThrow(() -> new UnauthorizedException(LOGIN_FAILED));
    IssuedRefreshToken refresh = refreshTokens.issue(account.user());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookies.issue(refresh.value(), refresh.maxAge()).toString())
        .body(tokenResponse(account.user(), account.credentials()));
  }

  /**
   * The cookie is read from the request here, not bound as a {@code @CookieValue} argument: Spring
   * MVC's TRACE diagnostics print handler arguments, and the raw refresh token must never be
   * logged.
   */
  @PostMapping("/refresh")
  public ResponseEntity<Object> refresh(HttpServletRequest request) {
    String cookie = presentedRefreshToken(request);
    if (cookie == null || cookie.isBlank()) {
      return sessionInvalid();
    }
    RotationResult result = refreshTokens.rotate(cookie);
    if (!(result instanceof RotationResult.Rotated rotated)) {
      return sessionInvalid();
    }
    LocalCredentials row = credentials.findById(rotated.user().getId()).orElse(null);
    if (row == null) {
      return sessionInvalid();
    }
    return ResponseEntity.ok()
        .header(
            HttpHeaders.SET_COOKIE,
            cookies.issue(rotated.token().value(), rotated.token().maxAge()).toString())
        .body(tokenResponse(rotated.user(), row));
  }

  /**
   * Revokes the family behind the cookie and, when the call carries a bearer token of the local
   * issuer, that token's {@code jti} - so the sign-out takes effect before the token expires. Never
   * an error: a missing or unknown cookie is simply cleared.
   */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletRequest request) {
    String cookie = presentedRefreshToken(request);
    if (cookie != null && !cookie.isBlank()) {
      refreshTokens.revokePresentedFamily(cookie, RevocationReason.LOGOUT);
    }
    currentLocalToken()
        .ifPresent(
            jwt ->
                revocation.revokeAccessToken(
                    jwt.getId(), UUID.fromString(jwt.getSubject()), jwt.getExpiresAt()));
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
        .build();
  }

  /**
   * Every refresh family of the account is revoked ({@code PASSWORD_CHANGED}) and the session this
   * call was made from continues in a fresh family: the answer is a sign-in - a token without
   * {@code pcr} and a new refresh cookie.
   */
  @PostMapping("/change-password")
  public ResponseEntity<LocalTokenResponse> changePassword(
      @Caller CurrentUser caller, @Valid @RequestBody LocalChangePasswordRequest request) {
    User user =
        userService
            .findBySubjectAndIssuer(caller.id().toString(), LocalIssuer.URN)
            .orElseThrow(
                () ->
                    new ConflictException(
                        "Das Passwort kann nur für ein lokales Konto geändert werden."));
    LocalCredentials changed =
        passwords.changePassword(user, request.getCurrentPassword(), request.getNewPassword());
    IssuedRefreshToken refresh = refreshTokens.issue(user);
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookies.issue(refresh.value(), refresh.maxAge()).toString())
        .body(tokenResponse(user, changed));
  }

  /**
   * Every token is minted no earlier than the account's {@code password_invalidated_before}: a
   * sign-in or a password change in the very second of a revocation would otherwise hand out a
   * token the validator refuses.
   */
  private LocalTokenResponse tokenResponse(User user, LocalCredentials row) {
    IssuedAccessToken token =
        accessTokens.issue(
            user, row.isPasswordChangeRequired(), row.getPasswordInvalidatedBefore());
    LocalTokenResponse response =
        new LocalTokenResponse(
            token.value(), token.expiresInSeconds(), row.isPasswordChangeRequired());
    if (row.isPasswordChangeRequired()) {
      response.setPasswordChangeReason(row.getPasswordChangeReason());
    }
    return response;
  }

  private ResponseEntity<Object> sessionInvalid() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
        .body(new ErrorResponse(SESSION_INVALID, HttpStatus.UNAUTHORIZED.value(), Instant.now()));
  }

  private static String presentedRefreshToken(HttpServletRequest request) {
    Cookie cookie = WebUtils.getCookie(request, LocalRefreshCookies.COOKIE_NAME);
    return cookie == null ? null : cookie.getValue();
  }

  private static Optional<Jwt> currentLocalToken() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof Jwt jwt
        && LocalIssuer.URN.equals(jwt.getClaimAsString("iss"))
        && jwt.getId() != null
        && jwt.getSubject() != null
        && jwt.getExpiresAt() != null) {
      return Optional.of(jwt);
    }
    return Optional.empty();
  }
}
