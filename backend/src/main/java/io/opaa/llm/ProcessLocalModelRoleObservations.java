package io.opaa.llm;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ModelRoleObservations} of one process, on its own clock - for a role built without a
 * database, as in a unit test. Records exactly what {@link JdbcModelRoleObservations} records.
 */
final class ProcessLocalModelRoleObservations implements ModelRoleObservations {

  private final Clock clock;
  private final Map<String, ModelRoleObservation> rows = new ConcurrentHashMap<>();

  ProcessLocalModelRoleObservations(Clock clock) {
    this.clock = clock;
  }

  @Override
  public Optional<ModelRoleObservation> current(String role) {
    return Optional.ofNullable(rows.get(role));
  }

  @Override
  public Optional<ModelRoleObservation> latest(String role) {
    return current(role);
  }

  @Override
  public void record(String role, ModelRoleObservation outcome, boolean probe) {
    rows.compute(
        role,
        (key, known) ->
            probe || known == null || !known.sameStateAs(outcome)
                ? new ModelRoleObservation(
                    outcome.baseUrl(),
                    outcome.model(),
                    outcome.reachable(),
                    outcome.timedOut(),
                    outcome.diagnostic(),
                    clock.instant())
                : known);
  }

  @Override
  public Instant now() {
    return clock.instant();
  }
}
