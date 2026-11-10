package io.opaa.query.toolloop;

import io.opaa.metadata.MetadataFilter;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Runs for the tests of this package. */
final class AgentRuns {

  static final AgentLimits LIMITS = new AgentLimits(5, 3, Duration.ofSeconds(120), 60_000);

  private AgentRuns() {}

  static AgentRun run(Set<UUID> scope) {
    return run(scope, MetadataFilter.NONE, LIMITS, Instant.parse("2026-11-10T08:00:00Z"));
  }

  static AgentRun run(
      Set<UUID> scope, MetadataFilter filter, AgentLimits limits, Instant startedAt) {
    return new AgentRun(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        scope,
        Set.of(UUID.randomUUID()),
        filter,
        limits,
        startedAt);
  }
}
