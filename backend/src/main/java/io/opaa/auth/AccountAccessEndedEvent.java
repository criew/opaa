package io.opaa.auth;

import java.util.Objects;
import java.util.UUID;

/**
 * Published inside the transaction of an act that takes a person's access away for good - the lock
 * of a local account (by an administrator or by the daily inactivity run), the redeemed handover to
 * a provider identity, and since #1818 the block of a provider account by the directory run. Every
 * merkmal that outlives such an act would be the most convenient way around the account lifecycle
 * (access-control.md, "Offboarding"), so each one is ended by a listener of this event instead of
 * by a call the next such act could forget.
 *
 * <p>A plain {@code @EventListener} therefore runs in the publisher's transaction: the lock and the
 * end of the merkmale commit together or not at all. Exactly one of {@code actorUserId} and {@code
 * systemActor} is set - a person acted, or a system process did.
 */
public record AccountAccessEndedEvent(User user, UUID actorUserId, String systemActor) {

  public AccountAccessEndedEvent {
    Objects.requireNonNull(user, "user");
    if ((actorUserId == null) == (systemActor == null)) {
      throw new IllegalArgumentException("exactly one of actorUserId and systemActor is set");
    }
  }

  /** The act of an administrator or of the person themselves (a redeemed handover). */
  public static AccountAccessEndedEvent by(User user, UUID actorUserId) {
    return new AccountAccessEndedEvent(user, Objects.requireNonNull(actorUserId), null);
  }

  /** The act of a system process - the daily inactivity run, a directory synchronisation run. */
  public static AccountAccessEndedEvent bySystem(User user, String systemActor) {
    return new AccountAccessEndedEvent(user, null, Objects.requireNonNull(systemActor));
  }
}
