package io.opaa.account;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * When the chats of an account locked by the administration at {@code lockedAt} are deleted at the
 * earliest, for the mail that announces the lock. Empty while the deletion is switched off or held
 * for the account. Implemented by the assistant module, which owns the chats.
 */
public interface LockedAccountChatDeletion {

  Optional<Instant> earliestDeletionAt(UUID userId, Instant lockedAt);
}
