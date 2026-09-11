package io.opaa.auth.local;

/**
 * Why a refresh token was revoked (ADR-0033, Entscheidungen 7 and 8) - an enum, never free text.
 * {@link #ROTATED} and {@link #REUSE_DETECTED} are the rotation mechanics; the rest name the act
 * that ended the session, so a session marker can tell the person why they have to sign in again.
 */
public enum RevocationReason {
  /** Presented and replaced by its successor - the normal case. */
  ROTATED,
  /** An already rotated token was presented again; the whole family is revoked. */
  REUSE_DETECTED,
  /** The person signed out. */
  LOGOUT,
  /** The person changed the password; other sessions end. */
  PASSWORD_CHANGED,
  /** An administrator reset the password. */
  ADMIN_RESET,
  /** The account was locked (by an administrator, after failed sign-ins or for inactivity). */
  ACCOUNT_LOCKED,
  /** The account was handed over to a provider identity. */
  HANDED_OVER,
  /** Any other administrative act, e.g. the bootstrap reset via {@code OPAA_LOCAL_ADMIN_RESET}. */
  ADMIN
}
