package io.opaa.auth.local;

import java.util.Locale;
import java.util.Objects;

/**
 * Why the local issuer refuses a structurally valid token (ADR-0033, Entscheidung 8): one of the
 * {@link LocalTokenMarkers} and, where the ADR names one, its cause.
 */
public record LocalTokenRejection(String marker, String cause) {

  public LocalTokenRejection {
    Objects.requireNonNull(marker, "marker");
  }

  /** {@code marker} or {@code marker:cause} - what {@code WWW-Authenticate} carries. */
  public String errorDescription() {
    return cause == null ? marker : marker + ":" + cause;
  }

  /** The cause of {@link LocalTokenMarkers#ACCOUNT_LOCKED}: the lock reason in lower case. */
  static String causeOf(LockReason reason) {
    return reason == null ? null : reason.name().toLowerCase(Locale.ROOT);
  }

  /**
   * The cause of {@link LocalTokenMarkers#SESSION_REVOKED} for a refresh family's revocation
   * reason; {@code null} for the mechanics ({@code ROTATED}) and the person's own sign-out.
   */
  static String causeOf(RevocationReason reason) {
    if (reason == null) {
      return null;
    }
    return switch (reason) {
      case ACCOUNT_LOCKED -> "admin_lock";
      case PASSWORD_CHANGED -> "password_changed";
      case ADMIN_RESET, ADMIN -> "admin_reset";
      case REUSE_DETECTED -> "reuse_detected";
      case HANDED_OVER -> "handed_over";
      case ROTATED, LOGOUT -> null;
    };
  }
}
