package io.opaa.connection.profile;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Since when persons are deactivated, as the lifecycle last recorded it - the start of the deletion
 * period of their private libraries. Implemented in {@code connection.account}.
 */
public interface DeactivationStarts {

  /** The recorded start for each of {@code userIds} that has one; others are absent. */
  Map<UUID, Instant> deactivatedSince(Collection<UUID> userIds);
}
