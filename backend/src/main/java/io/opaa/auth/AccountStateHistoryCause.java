package io.opaa.auth;

/**
 * Why an account's state interval was opened (#1818). Every cause belongs to exactly one {@link
 * AccountState}; {@code chk_account_state_history_cause_matches_state} holds that in the schema.
 */
public enum AccountStateHistoryCause {

  /** The directory reports the account, but as disabled. */
  DIRECTORY_DISABLED,

  /**
   * The directory no longer reports the account at all - a deletion in the directory, which
   * access-control.md treats exactly like a lock there ("Verschwindet ein Konto im Verzeichnis oder
   * wird es dort gesperrt").
   */
  DIRECTORY_ABSENT,

  /** The directory reports the account as usable again - the reversal of a block. */
  DIRECTORY_REACTIVATED;

  /** The state an interval opened with this cause carries. */
  public AccountState state() {
    return this == DIRECTORY_REACTIVATED ? AccountState.ACTIVE : AccountState.BLOCKED;
  }
}
