package io.opaa.llm;

import io.opaa.common.DatabaseClock;
import io.opaa.instance.InvalidationBus;
import io.opaa.instance.InvalidationSubscriber;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

/**
 * {@link ModelRoleObservations} in {@code model_role_observations}, one row per role, stamped with
 * the database clock. Each instance holds the rows in memory, bound to the invalidation bus
 * (ADR-0053, Entscheidung 5): a trigger announces every change of a state, and every instance reads
 * it anew; a probe that only renews {@code observed_at} is not announced. While the bus does not
 * listen, every read goes to the table. A load that a drop overtook is returned but not kept.
 */
@Component
public class JdbcModelRoleObservations implements ModelRoleObservations, InvalidationSubscriber {

  /** The topic the trigger on {@code model_role_observations} announces. */
  public static final String INVALIDATION_TOPIC = "model-role-observations";

  private final JdbcOperations jdbc;
  private final InvalidationBus bus;
  private final DatabaseClock databaseClock;
  private final Map<String, Optional<ModelRoleObservation>> held = new ConcurrentHashMap<>();
  private final AtomicLong generation = new AtomicLong();

  public JdbcModelRoleObservations(
      JdbcOperations jdbc, InvalidationBus bus, DatabaseClock databaseClock) {
    this.jdbc = jdbc;
    this.bus = bus;
    this.databaseClock = databaseClock;
  }

  @Override
  public Optional<ModelRoleObservation> current(String role) {
    if (!bus.isListening()) {
      return latest(role);
    }
    Optional<ModelRoleObservation> known = held.get(role);
    if (known != null) {
      return known;
    }
    long before = generation.get();
    Optional<ModelRoleObservation> loaded = latest(role);
    if (generation.get() == before) {
      held.put(role, loaded);
      if (generation.get() != before) {
        held.remove(role);
      }
    }
    return loaded;
  }

  @Override
  public Optional<ModelRoleObservation> latest(String role) {
    List<ModelRoleObservation> rows =
        jdbc.query(
            "SELECT base_url, model, reachable, timed_out, diagnostic, observed_at FROM"
                + " model_role_observations WHERE role = ?",
            (rs, row) -> observation(rs),
            role);
    return rows.stream().findFirst();
  }

  @Override
  public void record(String role, ModelRoleObservation outcome, boolean probe) {
    if (!probe && current(role).filter(known -> known.sameStateAs(outcome)).isPresent()) {
      return;
    }
    int written =
        jdbc.update(
            "INSERT INTO model_role_observations AS o (role, base_url, model, reachable,"
                + " timed_out, diagnostic, observed_at) VALUES (?, ?, ?, ?, ?, ?,"
                + " clock_timestamp()) ON CONFLICT (role) DO UPDATE SET base_url ="
                + " EXCLUDED.base_url, model = EXCLUDED.model, reachable = EXCLUDED.reachable,"
                + " timed_out = EXCLUDED.timed_out, diagnostic = EXCLUDED.diagnostic, observed_at"
                + " = EXCLUDED.observed_at WHERE CAST(? AS boolean) OR (o.base_url, o.model,"
                + " o.reachable, o.timed_out, o.diagnostic) IS DISTINCT FROM (EXCLUDED.base_url,"
                + " EXCLUDED.model, EXCLUDED.reachable, EXCLUDED.timed_out, EXCLUDED.diagnostic)",
            role,
            outcome.baseUrl(),
            outcome.model(),
            outcome.reachable(),
            outcome.timedOut(),
            outcome.diagnostic(),
            probe);
    if (written > 0) {
      // the own instance reads its write at once rather than after the announcement
      invalidateAll();
    }
  }

  @Override
  public Instant now() {
    return databaseClock.now();
  }

  @Override
  public Set<String> invalidationTopics() {
    return Set.of(INVALIDATION_TOPIC);
  }

  @Override
  public void invalidateAll() {
    generation.incrementAndGet();
    held.clear();
  }

  private static ModelRoleObservation observation(ResultSet rs) throws SQLException {
    return new ModelRoleObservation(
        rs.getString("base_url"),
        rs.getString("model"),
        rs.getBoolean("reachable"),
        rs.getBoolean("timed_out"),
        rs.getString("diagnostic"),
        rs.getObject("observed_at", OffsetDateTime.class).toInstant());
  }
}
