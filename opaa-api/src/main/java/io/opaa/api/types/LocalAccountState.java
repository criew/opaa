package io.opaa.api.types;

/**
 * The derived state of a local account (ADR-0033, Entscheidung 3) - see {@link
 * LocalCredentials#state}. Only {@link #ACTIVE} can sign in.
 */
public enum LocalAccountState {
  /** No password yet, or the address is not confirmed - the invitation is not complete. */
  INVITED,
  ACTIVE,
  /** Locked by an administrator, after failed sign-ins or for inactivity. */
  LOCKED,
  /** The expiry date has passed; the account is kept, not deleted. */
  EXPIRED
}
