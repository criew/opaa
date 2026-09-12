package io.opaa.api.types;

/**
 * Why a password change is forced (ADR-0033, Entscheidung 3) - shown to the person as a plain
 * sentence, because "reset by the administrator" is something else than "set your first password".
 * Shared between the {@code local_credentials} row and the API (the {@code pcr} filter names it,
 * the login response carries it), mirrored by the spec schema of the same name.
 */
public enum PasswordChangeReason {
  /** First sign-in with a generated initial or one-time password. */
  INITIAL,
  /** An administrator reset the password. */
  ADMIN_RESET,
  /** A security event: the operator asked for a new password. */
  SECURITY
}
