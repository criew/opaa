package io.opaa.searchadmin;

import io.opaa.api.types.ModelRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link SearchStatusProbeResults} of one process, on its own clock - for a status service built
 * without a database, as in a unit test. Shares exactly what {@link JdbcSearchStatusProbeResults}
 * shares.
 */
final class ProcessLocalSearchStatusProbeResults implements SearchStatusProbeResults {

  private record Shared(ModelRoleStatus status, Instant at) {}

  private final Clock clock;
  private final Map<ModelRole, Shared> shared = new ConcurrentHashMap<>();

  ProcessLocalSearchStatusProbeResults(Clock clock) {
    this.clock = clock;
  }

  @Override
  public Optional<ModelRoleStatus> fresh(ModelRole role, Duration maxAge) {
    Shared known = shared.get(role);
    return known != null && clock.instant().isBefore(known.at().plus(maxAge))
        ? Optional.of(known.status())
        : Optional.empty();
  }

  @Override
  public ModelRoleStatus share(ModelRoleStatus probed, Duration maxAge) {
    return shared
        .compute(
            probed.role(),
            (role, known) ->
                known != null && clock.instant().isBefore(known.at().plus(maxAge))
                    ? known
                    : new Shared(probed, clock.instant()))
        .status();
  }

  @Override
  public void forget() {
    shared.clear();
  }
}
