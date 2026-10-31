package io.opaa.chat;

import io.opaa.api.types.ChatCleanupAfterLockStatus;
import io.opaa.api.types.ChatCleanupHoldPurpose;
import java.time.Instant;
import java.util.UUID;

/**
 * Where the deletion of one local account's chats stands, as the single view of the account shows
 * it; {@code earliestDeletionAt} only with {@link ChatCleanupAfterLockStatus#SCHEDULED}, {@code
 * hold} only while a hold runs, {@code pendingRequest} only while a request awaits approval.
 */
public record LockedAccountChatCleanup(
    ChatCleanupAfterLockStatus status,
    boolean held,
    int deleteAfterDays,
    int holdMaxDays,
    Instant earliestDeletionAt,
    Hold hold,
    PendingRequest pendingRequest) {

  /**
   * A running hold; {@code purpose} and {@code reason} null for one placed before holds were
   * approved in pairs, {@code heldUntil} null until the nightly run bounds such a hold.
   */
  public record Hold(
      ChatCleanupHoldPurpose purpose, String reason, Instant heldSince, Instant heldUntil) {}

  /**
   * A request awaiting a second administrator; {@code extension} when approving it now extends the
   * running hold.
   */
  public record PendingRequest(
      ChatCleanupHoldPurpose purpose,
      String reason,
      Instant requestedAt,
      UUID requestedBy,
      boolean extension) {}
}
