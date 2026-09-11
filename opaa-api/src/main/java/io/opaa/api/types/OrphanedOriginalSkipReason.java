package io.opaa.api.types;

/** Why the orphan cleanup left a named locator alone instead of removing its original. */
public enum OrphanedOriginalSkipReason {
  /** A document row of the library points to it. */
  REFERENCED,
  /** The original is younger than the configured grace period. */
  WITHIN_GRACE_PERIOD,
  /** The library's storage area holds no original under this locator. */
  NOT_IN_STORE,
  /** The store did not confirm the removal; the original may still be there. */
  DELETE_FAILED
}
