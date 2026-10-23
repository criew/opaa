package io.opaa.chat;

import io.opaa.api.types.ChatCleanupAfterLockStatus;
import java.time.Instant;

/**
 * Where the deletion of one local account's chats stands, as the single view of the account shows
 * it; {@code earliestDeletionAt} only with {@link ChatCleanupAfterLockStatus#SCHEDULED}.
 */
public record LockedAccountChatCleanup(
    ChatCleanupAfterLockStatus status,
    boolean held,
    int deleteAfterDays,
    Instant earliestDeletionAt) {}
