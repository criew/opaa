package io.opaa.auth.local;

import io.opaa.api.types.LockReason;
import java.util.Locale;
import java.util.Objects;

/**
 * Why the local issuer refuses a structurally valid token (ADR-0033, Entscheidung 8): one of the
 * {@link LocalTokenMarkers} and, where the ADR names one, its cause.
 */
public record LocalTokenRejection(String marker, String cause) {

  /** The cause of {@link RevocationReason#HANDED_OVER} (ADR-0033, Entscheidung 12). */
  public static final String HANDED_OVER_CAUSE = "handed_over";

  /**
   * The cause of {@link RevocationReason#ADMIN}, the administrative act that is not a password
   * reset - kept apart from {@code admin_reset} so no sentence claims a password was reset when
   * none was.
   */
  private static final String ADMIN_ACTION_CAUSE = "admin_action";

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
      case ADMIN_RESET -> "admin_reset";
      case ADMIN -> ADMIN_ACTION_CAUSE;
      case REUSE_DETECTED -> "reuse_detected";
      case HANDED_OVER -> HANDED_OVER_CAUSE;
      case ROTATED, LOGOUT -> null;
    };
  }
}
