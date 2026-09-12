package io.opaa.api.types;

/**
 * The activity of a local account as a class, never as a timestamp (ADR-0033, Entscheidung 11):
 * derived from the throttled activity timestamp {@code users.last_login_at}, which is no sign-in
 * time and is never output as one. The 90-day class is the review filter the ADR names and is
 * independent of {@code local_auth_settings.inactive_days}, after which the daily run locks.
 */
public enum LocalAccountActivity {
  /** No request since the account was created. */
  NEVER,
  /** No request for at least 90 days. */
  INACTIVE_90_DAYS,
  ACTIVE
}
