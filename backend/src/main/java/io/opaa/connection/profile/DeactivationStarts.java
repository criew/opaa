package io.opaa.connection.profile;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Since when the deletion period of persons' private libraries runs: from their current
 * deactivation by an act that ends the account, never from an absence. Implemented in {@code
 * connection.account}.
 */
public interface DeactivationStarts {

  /** The start for each of {@code userIds} whose period runs now; others are absent. */
  Map<UUID, Instant> deactivatedSince(Collection<UUID> userIds);
}
