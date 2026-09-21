package io.opaa.auth.local;

import io.opaa.auth.User;
import java.util.Objects;
import java.util.UUID;

/**
 * Published inside the transaction of an act that takes a person's access away - the lock of an
 * account (by an administrator, by the daily inactivity run, or by the directory synchronisation,
 * #1818) and the redeemed handover to a provider identity. Every merkmal that outlives such an act
 * would be the most convenient way around the account lifecycle (access-control.md, "Offboarding"),
 * so each one is ended by a listener of this event instead of by a call the next such act could
 * forget.
 *
 * <p>A plain {@code @EventListener} therefore runs in the publisher's transaction: the lock and the
 * end of the merkmale commit together or not at all. Exactly one of {@code actorUserId} and {@code
 * systemActor} is set - an administrator acted, or the daily run did.
 */
public record LocalAccountAccessEndedEvent(User user, UUID actorUserId, String systemActor) {

  public LocalAccountAccessEndedEvent {
    Objects.requireNonNull(user, "user");
    if ((actorUserId == null) == (systemActor == null)) {
      throw new IllegalArgumentException("exactly one of actorUserId and systemActor is set");
    }
  }

  /** The act of an administrator or of the person themselves (a redeemed handover). */
  public static LocalAccountAccessEndedEvent by(User user, UUID actorUserId) {
    return new LocalAccountAccessEndedEvent(user, Objects.requireNonNull(actorUserId), null);
  }

  /** The act of a system process - the daily inactivity run, or a directory synchronisation run. */
  public static LocalAccountAccessEndedEvent bySystem(User user, String systemActor) {
    return new LocalAccountAccessEndedEvent(user, null, Objects.requireNonNull(systemActor));
  }
}
