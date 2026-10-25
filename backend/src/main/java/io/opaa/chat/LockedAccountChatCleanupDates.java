package io.opaa.chat;

import io.opaa.account.LockedAccountChatDeletion;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The earliest deletion of a locked account's chats: the later of the lock and the moment the
 * deletion was switched on, plus the period; switched on but not counting yet, the next run starts
 * the count, so now stands in for that moment. Whether the account has chats and whether an open
 * Klärung suspends the deletion play no part, so the date tells nothing about either.
 */
@Component
class LockedAccountChatCleanupDates implements LockedAccountChatDeletion {

  private final LockedAccountChatCleanupStore store;
  private final LockedAccountChatCleanupSettings settings;
  private final Clock clock;

  LockedAccountChatCleanupDates(
      LockedAccountChatCleanupStore store, LockedAccountChatCleanupSettings settings, Clock clock) {
    this.store = store;
    this.settings = settings;
    this.clock = clock;
  }

  @Override
  public Optional<Instant> earliestDeletionAt(UUID userId, Instant lockedAt) {
    LockedAccountChatCleanupPolicy policy = settings.current();
    if (!policy.enabled() || store.isHeld(userId)) {
      return Optional.empty();
    }
    return Optional.of(dueAt(lockedAt, policy));
  }

  /**
   * The earliest deletion under {@code policy} of chats locked at {@code lockedAt}, holds aside.
   */
  Instant dueAt(Instant lockedAt, LockedAccountChatCleanupPolicy policy) {
    Instant since = store.countingSince().orElseGet(() -> Instant.now(clock));
    Instant start = since.isAfter(lockedAt) ? since : lockedAt;
    return start.plus(policy.period());
  }
}
