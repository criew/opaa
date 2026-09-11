package io.opaa.library;

/**
 * Why {@link OrphanedOriginalCleanupService#delete} left a named locator alone. Mirrored by the
 * API's {@code OrphanedOriginalSkipReason}; keep both in sync.
 */
public enum OrphanedOriginalSkipReason {
  /** A document row of the library points to it now. */
  REFERENCED,
  /** The original is younger than the configured grace period. */
  WITHIN_GRACE_PERIOD,
  /** The library's storage area holds no original under this locator. */
  NOT_IN_STORE,
  /** The store refused the removal; the original is still there. */
  DELETE_FAILED
}
