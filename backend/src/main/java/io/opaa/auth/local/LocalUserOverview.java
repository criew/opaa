package io.opaa.auth.local;

import io.opaa.api.types.LocalAccountActivity;
import io.opaa.api.types.LocalAccountState;
import io.opaa.auth.User;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A local account as the administration sees it: the two rows, the derived state and the activity
 * as a class (ADR-0033, Entscheidung 11) - the throttled activity timestamp itself never leaves the
 * domain.
 */
public record LocalUserOverview(
    User user,
    LocalCredentials credentials,
    LocalAccountState state,
    LocalAccountActivity activity) {

  /** The class boundary the ADR names: "länger als 90 Tage nicht genutzt". */
  public static final Duration INACTIVITY_CLASS_WINDOW = Duration.ofDays(90);

  public LocalUserOverview {
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(credentials, "credentials");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(activity, "activity");
  }

  public static LocalUserOverview of(User user, LocalCredentials credentials, Instant now) {
    return new LocalUserOverview(user, credentials, credentials.state(now), activityOf(user, now));
  }

  static LocalAccountActivity activityOf(User user, Instant now) {
    Instant lastActivity = user.getLastLoginAt();
    if (lastActivity == null) {
      return LocalAccountActivity.NEVER;
    }
    return lastActivity.isBefore(now.minus(INACTIVITY_CLASS_WINDOW))
        ? LocalAccountActivity.INACTIVE_90_DAYS
        : LocalAccountActivity.ACTIVE;
  }

  public boolean isInactive() {
    return activity != LocalAccountActivity.ACTIVE;
  }
}
