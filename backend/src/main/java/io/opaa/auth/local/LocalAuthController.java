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
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

  public LocalAuthController(
      LocalLoginService loginService,
      LocalAccessTokenService accessTokens,
      LocalRefreshTokenService refreshTokens,
      LocalTokenRevocationService revocation,
      LocalPasswordService passwords,
      LocalCredentialsRepository credentials,
      UserService userService,
      LocalRefreshCookies cookies) {
    this.loginService = loginService;
    this.accessTokens = accessTokens;
    this.refreshTokens = refreshTokens;
    this.revocation = revocation;
    this.passwords = passwords;
    this.credentials = credentials;
    this.userService = userService;
    this.cookies = cookies;
  }

  @PostMapping("/login")
  public ResponseEntity<LocalTokenResponse> login(@Valid @RequestBody LocalLoginRequest request) {
    AuthenticatedLocalAccount account =
        loginService
            .authenticate(request.getEmail(), request.getPassword())
            .orElseThrow(() -> new UnauthorizedException(LOGIN_FAILED));
    IssuedRefreshToken refresh = refreshTokens.issue(account.user());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookies.issue(refresh.value(), refresh.maxAge()).toString())
        .body(tokenResponse(account.user(), account.credentials()));
  }

  @PostMapping("/refresh")
  public ResponseEntity<Object> refresh(
      @CookieValue(name = LocalRefreshCookies.COOKIE_NAME, required = false) String cookie) {
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
  public ResponseEntity<Void> logout(
      @CookieValue(name = LocalRefreshCookies.COOKIE_NAME, required = false) String cookie) {
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
   * The family behind the cookie of this very call survives the change; every other one is revoked.
   * Answers like a sign-in with a token that no longer carries {@code pcr}; the cookie is left as
   * it is.
   */
  @PostMapping("/change-password")
  public LocalTokenResponse changePassword(
      @Caller CurrentUser caller,
      @Valid @RequestBody LocalChangePasswordRequest request,
      @CookieValue(name = LocalRefreshCookies.COOKIE_NAME, required = false) String cookie) {
    User user =
        userService
            .findBySubjectAndIssuer(caller.id().toString(), LocalIssuer.URN)
            .orElseThrow(
                () ->
                    new ConflictException(
                        "Das Passwort kann nur für ein lokales Konto geändert werden."));
    UUID keepFamilyId =
        refreshTokens
            .findPresented(cookie)
            .filter(row -> row.getUserId().equals(user.getId()))
            .map(LocalRefreshToken::getFamilyId)
            .orElse(null);
    LocalCredentials changed =
        passwords.changePassword(
            user, request.getCurrentPassword(), request.getNewPassword(), keepFamilyId);
    return tokenResponse(user, changed);
  }

  private LocalTokenResponse tokenResponse(User user, LocalCredentials row) {
    IssuedAccessToken token = accessTokens.issue(user, row.isPasswordChangeRequired());
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
