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
  private final LockedAccountChatCleanupProperties properties;
  private final Clock clock;

  LockedAccountChatCleanupDates(
      LockedAccountChatCleanupStore store,
      LockedAccountChatCleanupProperties properties,
      Clock clock) {
    this.store = store;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public Optional<Instant> earliestDeletionAt(UUID userId, Instant lockedAt) {
    if (!properties.enabled() || store.isHeld(userId)) {
      return Optional.empty();
    }
    return Optional.of(dueAt(lockedAt));
  }

  /** The earliest deletion of chats locked at {@code lockedAt}, holds aside. */
  Instant dueAt(Instant lockedAt) {
    Instant since = store.countingSince().orElseGet(() -> Instant.now(clock));
    Instant start = since.isAfter(lockedAt) ? since : lockedAt;
    return start.plus(properties.period());
  }
}
