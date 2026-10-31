package io.opaa.audit;

/**
 * What {@code audit_chat_cleanup_hold_purpose_counts} counts of a hold of the deletion of a locked
 * account's chats, each in the month it happens. Mirrored by the check constraint {@code
 * chk_audit_chat_cleanup_hold_purpose_counts_transition}.
 */
public enum ChatCleanupHoldTransition {
  /** A request to hold or to extend. */
  REQUESTED,
  /** The approval of a new hold; the hold takes effect. */
  APPROVED,
  /** The approval of the extension of a running hold. */
  EXTENDED,
  /** A request rejected, withdrawn or dropped unanswered. */
  REJECTED,
  /** A hold that ended by itself at its maximum duration. */
  EXPIRED
}
