package io.opaa.account;

import io.opaa.auth.User;
import java.util.Objects;
import java.util.UUID;

/**
 * Published inside the transaction of an act that ends every session of a local account because its
 * credentials or a session may be in someone else's hands - the person's own password change or
 * reset, an administrator's reset and a detected refresh replay ({@code cause}). Unlike {@link
 * LocalAccountAccessEndedEvent} the account stays usable; what a session could have created on its
 * own behalf (an access token) ends with it, so a listener runs in the publisher's transaction and
 * the new password and the end of those merkmale commit together. Exactly one of {@code
 * actorUserId} and {@code systemActor} is set.
 */
public record LocalAccountSessionsEndedEvent(
    User user, RevocationReason cause, UUID actorUserId, String systemActor) {

  public LocalAccountSessionsEndedEvent {
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(cause, "cause");
    if ((actorUserId == null) == (systemActor == null)) {
      throw new IllegalArgumentException("exactly one of actorUserId and systemActor is set");
    }
  }

  /** The act of the person themselves or of an administrator. */
  public static LocalAccountSessionsEndedEvent by(
      User user, RevocationReason cause, UUID actorUserId) {
    return new LocalAccountSessionsEndedEvent(
        user, cause, Objects.requireNonNull(actorUserId), null);
  }

  /** The act of a system process - the replay detection, or the start-up reset. */
  public static LocalAccountSessionsEndedEvent bySystem(
      User user, RevocationReason cause, String systemActor) {
    return new LocalAccountSessionsEndedEvent(
        user, cause, null, Objects.requireNonNull(systemActor));
  }
}
