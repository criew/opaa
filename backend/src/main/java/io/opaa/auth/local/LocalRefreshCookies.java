package io.opaa.auth.local;

import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The {@code Set-Cookie} of the refresh token (ADR-0033, Entscheidung 7): {@code HttpOnly} keeps it
 * from scripts, {@code SameSite=Strict} and the narrow path keep it to the refresh, logout and
 * later handover endpoints, {@code Secure} follows {@code opaa.auth.local.cookie-secure} - off only
 * for local HTTP, and never silently ({@link LocalAuthSecretGuard}).
 */
@Component
public class LocalRefreshCookies {

  public static final String COOKIE_NAME = "opaa_refresh";
  public static final String COOKIE_PATH = "/api/v1/auth/local";
  private static final String SAME_SITE = "Strict";

  private final boolean secure;

  public LocalRefreshCookies(LocalAuthProperties properties) {
    this.secure = Boolean.TRUE.equals(properties.cookieSecure());
  }

  public ResponseCookie issue(String rawToken, Duration maxAge) {
    return base(rawToken).maxAge(maxAge).build();
  }

  /** Removes the cookie - sign-out and every refused refresh. */
  public ResponseCookie clear() {
    return base("").maxAge(0).build();
  }

  private ResponseCookie.ResponseCookieBuilder base(String value) {
    return ResponseCookie.from(COOKIE_NAME, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite(SAME_SITE)
        .path(COOKIE_PATH);
  }
}
