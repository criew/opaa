package io.opaa.knowledge;

/** Why a private library is erased; the cause its erasure proof names. */
public enum ErasureCause {
  /** Its owner deleted it herself. */
  OWNER_REQUEST,
  /** Its owner's account stayed deactivated beyond the deletion period. */
  DELETION_PERIOD_EXPIRED
}
