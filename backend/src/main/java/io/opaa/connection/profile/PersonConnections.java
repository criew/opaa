package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionEndCause;
import java.util.UUID;

/**
 * The port through which the profile administration reaches persons' connected accounts on a
 * profile, by number and as a whole only; the account package answers it.
 */
public interface PersonConnections {

  /** The persons' connections on {@code profileId} that are not disconnected. */
  long countUnder(UUID profileId);

  /**
   * Ends every person's connection on {@code profileId} for {@code cause}, caused by {@code
   * actorUserId}, with one connection-log entry each, in the caller's transaction.
   *
   * @return how many connections ended
   */
  int endAllUnder(UUID profileId, ConnectionEndCause cause, UUID actorUserId);
}
