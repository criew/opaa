package io.opaa.group;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * When the directory of a provider was last read - the delay a member may see for themselves
 * (ADR-0036, Entscheidung 3). Implemented by the directory synchronisation in {@code
 * io.opaa.directory.sync}, which sits above this package.
 */
public interface DirectorySyncRuns {

  /** The last run of the provider's directory in the organization, empty before the first. */
  Optional<Instant> lastRunAt(UUID organizationId, UUID providerId);
}
