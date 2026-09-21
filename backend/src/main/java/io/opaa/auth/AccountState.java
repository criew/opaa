package io.opaa.auth;

/**
 * Whether an account may still be used (#1818, ADR-0036 Entscheidung 3). Not a column of {@code
 * users}: the open interval of {@code account_state_history} carries it, so the state and the
 * evidence of it can never disagree - see {@link AccountStateService}. An account with no interval
 * at all has never been blocked and is {@link #ACTIVE}.
 */
public enum AccountState {

  /** Usable. */
  ACTIVE,

  /**
   * The directory no longer reports this account as usable. Rights, memberships and ownership stay
   * untouched - only the way in ends (ADR-0036, Entscheidung 6).
   */
  BLOCKED
}
