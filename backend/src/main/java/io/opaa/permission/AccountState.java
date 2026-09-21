package io.opaa.permission;

/**
 * The two states an account's rights history distinguishes (#1818, ADR-0036 Entscheidung 8): it may
 * exercise its rights, or its access is taken away while the state holds. Mirrored by the database
 * check constraint {@code chk_account_state_history_state}; keep both in sync.
 */
public enum AccountState {
  ACTIVE,
  LOCKED
}
