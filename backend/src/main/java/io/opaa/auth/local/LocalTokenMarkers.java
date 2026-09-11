package io.opaa.auth.local;

/**
 * The {@code error_description} markers a refused local token carries in {@code WWW-Authenticate}
 * (ADR-0033, Entscheidung 8), next to the registry's {@code unknown_issuer}: the SPA tells them
 * apart from an expired token, starts no renewal and shows the reason. Markers with a cause are
 * written {@code marker:cause} ({@link LocalTokenRejection#errorDescription()}).
 */
public final class LocalTokenMarkers {

  /** The management is switched off and the account is no {@code SYSTEM_ADMIN}. */
  public static final String LOCAL_ACCOUNTS_DISABLED = "local_accounts_disabled";

  /** Cause: {@code admin}, {@code failed_logins} or {@code inactivity}. */
  public static final String ACCOUNT_LOCKED = "account_locked";

  public static final String ACCOUNT_EXPIRED = "account_expired";

  /**
   * Cause, when one is known: {@code admin_lock}, {@code password_changed}, {@code admin_reset},
   * {@code reuse_detected}, {@code handed_over}; none after the person's own sign-out.
   */
  public static final String SESSION_REVOKED = "session_revoked";

  /** A validly signed token whose subject has no local account - never provisioned. */
  public static final String UNKNOWN_ACCOUNT = "unknown_account";

  /** A token of the local issuer without {@code jti}, {@code iat} or a UUID subject. */
  public static final String MALFORMED_TOKEN = "malformed_token";

  private LocalTokenMarkers() {}
}
