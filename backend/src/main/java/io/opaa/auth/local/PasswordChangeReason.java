package io.opaa.auth.local;

/**
 * Why a password change is forced (ADR-0033, Entscheidung 3) - shown to the person as a plain
 * sentence, because "reset by the administrator" is something else than "set your first password".
 */
public enum PasswordChangeReason {
  /** First sign-in with a generated initial or one-time password. */
  INITIAL,
  /** An administrator reset the password. */
  ADMIN_RESET,
  /** A security event: the operator asked for a new password. */
  SECURITY
}
