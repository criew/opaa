package io.opaa.permission;

/**
 * Why an {@link AccountStateHistory} interval was opened: for a {@code LOCKED} one the reason the
 * account is unusable, by the precedence of {@code io.opaa.auth.AccountUsability}; for an {@code
 * ACTIVE} one what made it usable again. Written by the database function {@code
 * account_state_reconcile} and mirrored by the check constraint {@code
 * chk_account_state_history_cause}; keep all three in sync.
 */
public enum AccountStateHistoryCause {
  /**
   * The account was active from its creation on - written retroactively, and only together with the
   * first state change of an account created usable, so that its chain starts at {@code
   * users.created_at} without a row for every account that never changed.
   */
  ACCOUNT_CREATED,
  /** The directory reports the account as disabled, or no longer reports it at all. */
  DIRECTORY_LOCKED,
  /** The directory reports the account as enabled again; the lock is taken back. */
  DIRECTORY_UNLOCKED,
  /** A local account locked by the administration. */
  ADMIN_LOCKED,
  /** A local account locked for inactivity. */
  INACTIVITY_LOCKED,
  /** A local account past its expiry date. */
  LOCAL_EXPIRED,
  /** A local account whose invitation or address confirmation is not completed yet. */
  LOCAL_INVITED,
  /** A regular local account while the local account management is switched off. */
  LOCAL_ACCOUNTS_DISABLED,
  /** An account of a switched-off sign-in provider. */
  PROVIDER_DISABLED,
  /** An account whose sign-in provider was deleted. */
  PROVIDER_REMOVED,
  /** Usable again for any reason other than the directory's: lifted, extended, switched on. */
  ACCOUNT_REACTIVATED
}
