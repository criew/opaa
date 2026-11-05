package io.opaa.knowledge;

/** Why a private library is erased; the cause its erasure proof names. */
public enum ErasureCause {
  /** Its owner deleted it herself. */
  OWNER_REQUEST,
  /** Its owner's account stayed deactivated beyond the deletion period. */
  DELETION_PERIOD_EXPIRED,
  /** Its owner's personal reference was removed on a request under Art. 17 GDPR. */
  ACCOUNT_ERASED
}
