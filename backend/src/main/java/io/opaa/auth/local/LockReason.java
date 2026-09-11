package io.opaa.auth.local;

/** Why a local account is locked (ADR-0033, Entscheidungen 3, 9 and 11). */
public enum LockReason {
  ADMIN,
  /** Too many failed sign-ins; ends with {@link LocalCredentials#getLockoutUntil()}. */
  FAILED_LOGINS,
  /** No activity for the configured number of days; the bootstrap account is exempt. */
  INACTIVITY
}
