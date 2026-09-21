package io.opaa.permission;

/**
 * Why an {@link AccountStateHistory} interval was opened. Mirrored by the database check constraint
 * {@code chk_account_state_history_cause} (changelog 064); keep both in sync.
 */
public enum AccountStateHistoryCause {
  /**
   * The account was active from its creation on - written retroactively, and only together with the
   * first state change, so that the chain of a locked account starts at {@code users.created_at}
   * without an interval at creation time making every account undeletable (changelog 064).
   */
  ACCOUNT_CREATED,
  /** The directory reports the account as disabled, or no longer reports it at all. */
  DIRECTORY_LOCKED,
  /** The directory reports the account as enabled again; the lock is taken back. */
  DIRECTORY_UNLOCKED
}
