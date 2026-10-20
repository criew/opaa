package io.opaa.searchadmin;

import io.opaa.api.types.ModelRole;
import io.opaa.api.types.ModelRoleCondition;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

/**
 * {@link SearchStatusProbeResults} in {@code search_status_probes}, one row per role, its age
 * judged by the database clock. A result is replaced only once it is older than the bound the
 * caller names, so two instances probing at once agree on the one that was stored first. A stored
 * condition this version does not know counts as no result.
 */
@Component
public class JdbcSearchStatusProbeResults implements SearchStatusProbeResults {

  private static final String FRESH =
      "observed_at > clock_timestamp() - make_interval(secs => CAST(? AS double precision))";

  private final JdbcOperations jdbc;

  public JdbcSearchStatusProbeResults(JdbcOperations jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ModelRoleStatus> fresh(ModelRole role, Duration maxAge) {
    List<Optional<ModelRoleStatus>> rows =
        jdbc.query(
            "SELECT condition, base_url, model, detail FROM search_status_probes WHERE role = ?"
                + " AND "
                + FRESH,
            (rs, row) -> {
              String endpoint = rs.getString("base_url");
              String model = rs.getString("model");
              String detail = rs.getString("detail");
              return condition(rs.getString("condition"))
                  .map(condition -> new ModelRoleStatus(role, condition, endpoint, model, detail));
            },
            role.name(),
            seconds(maxAge));
    return rows.stream().flatMap(Optional::stream).findFirst();
  }

  @Override
  public ModelRoleStatus share(ModelRoleStatus probed, Duration maxAge) {
    jdbc.update(
        "INSERT INTO search_status_probes AS p (role, condition, base_url, model, detail,"
            + " observed_at) VALUES (?, ?, ?, ?, ?, clock_timestamp()) ON CONFLICT (role) DO"
            + " UPDATE SET condition = EXCLUDED.condition, base_url = EXCLUDED.base_url, model ="
            + " EXCLUDED.model, detail = EXCLUDED.detail, observed_at = EXCLUDED.observed_at"
            + " WHERE NOT (p."
            + FRESH
            + ")",
        probed.role().name(),
        probed.condition().name(),
        probed.endpoint(),
        probed.modelIdentifier(),
        probed.detail(),
        seconds(maxAge));
    return fresh(probed.role(), maxAge).orElse(probed);
  }

  @Override
  public void forget() {
    jdbc.update("DELETE FROM search_status_probes");
  }

  private static Optional<ModelRoleCondition> condition(String stored) {
    try {
      return Optional.of(ModelRoleCondition.valueOf(stored));
    } catch (IllegalArgumentException unknown) {
      return Optional.empty();
    }
  }

  private static double seconds(Duration duration) {
    return duration.toMillis() / 1000.0;
  }
}
