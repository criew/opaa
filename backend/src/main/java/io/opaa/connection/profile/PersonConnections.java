package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionEndCause;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The port through which the profile administration reaches persons' connected accounts on a
 * profile, as a whole only; the account package answers it. Its numbers are exact and leave this
 * package only masked, through {@link PersonNumbers}.
 */
public interface PersonConnections {

  /** The connected and the expired accounts of each of {@code profileIds} that has any. */
  Map<UUID, StateCounts> countsAmong(Collection<UUID> profileIds);

  /**
   * Ends every person's connection on {@code profileId} for {@code cause}, caused by {@code
   * actorUserId}, with one connection-log entry each, in the caller's transaction.
   */
  void endAllUnder(UUID profileId, ConnectionEndCause cause, UUID actorUserId);

  /**
   * The connections held, not disconnected, by any of {@code userIds} on any profile, and the
   * private libraries they own.
   */
  PersonTotals totalsOf(Collection<UUID> userIds);

  /** The exact numbers of one profile, for {@link PersonNumbers} only. */
  record StateCounts(long connected, long expired) {}

  /** The exact numbers of a set of persons, for {@link PersonNumbers} only. */
  record PersonTotals(long connections, long privateLibraries) {}
}
