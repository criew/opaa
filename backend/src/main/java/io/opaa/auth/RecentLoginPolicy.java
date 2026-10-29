package io.opaa.auth;

import io.opaa.common.AccessDeniedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Whether the sign-in behind a request is recent: the bearer token's {@code auth_time} (OIDC Core,
 * seconds since the epoch) lies at most {@code opaa.auth.recent-login.max-age} back. The proof
 * lives in the token, never in server state (ADR-0005, ADR-0053). A token without the claim - one
 * the local issuer minted on a refresh, a provider that does not send it, an external access token
 * - never counts; neither does an {@code auth_time} more than {@link #CLOCK_SKEW} ahead. A bean of
 * {@link RecentLoginWebConfig}, so a service may also call it directly.
 */
public class RecentLoginPolicy {

  /** The {@code code} of the refusal. */
  public static final String CODE = "RECENT_LOGIN_REQUIRED";

  /** The claim that names the time of the sign-in. */
  public static final String AUTH_TIME_CLAIM = "auth_time";

  static final String MESSAGE = "Bitte bestätigen Sie Ihre Identität durch erneute Anmeldung.";

  /** The same tolerance Spring's own timestamp validation grants a provider's clock. */
  static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

  /** The {@code reason} of the refusal. */
  public enum Reason {
    /** The token names no time of sign-in. */
    AUTH_TIME_MISSING,
    /** The sign-in it names is older than the window, or implausibly in the future. */
    AUTH_TIME_TOO_OLD
  }

  private final RecentLoginProperties properties;
  private final Clock clock;

  public RecentLoginPolicy(RecentLoginProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  /** Throws {@link AccessDeniedException} with {@link #CODE} unless the sign-in is recent. */
  public void requireRecentLogin(Authentication authentication) {
    Instant authTime =
        authentication != null && authentication.getPrincipal() instanceof Jwt jwt
            ? authTimeOf(jwt)
            : null;
    if (authTime == null) {
      throw refusal(Reason.AUTH_TIME_MISSING);
    }
    Instant now = clock.instant();
    if (authTime.isBefore(now.minus(properties.maxAge()))
        || authTime.isAfter(now.plus(CLOCK_SKEW))) {
      throw refusal(Reason.AUTH_TIME_TOO_OLD);
    }
  }

  /**
   * {@link #requireRecentLogin(Authentication)} for the request being served, for a service whose
   * demand depends on what the request changes, where a handler annotation cannot decide.
   */
  public void requireRecentLogin() {
    requireRecentLogin(SecurityContextHolder.getContext().getAuthentication());
  }

  /** The time of sign-in the token names, or {@code null} when it names none readable. */
  static Instant authTimeOf(Jwt jwt) {
    Object value = jwt.getClaims().get(AUTH_TIME_CLAIM);
    return switch (value) {
      case Instant instant -> instant;
      case Date date -> date.toInstant();
      case Number seconds -> Instant.ofEpochSecond(seconds.longValue());
      case String text when text.matches("\\d{1,12}") ->
          Instant.ofEpochSecond(Long.parseLong(text));
      case null, default -> null;
    };
  }

  private static AccessDeniedException refusal(Reason reason) {
    return new AccessDeniedException(MESSAGE, CODE, reason.name());
  }
}
