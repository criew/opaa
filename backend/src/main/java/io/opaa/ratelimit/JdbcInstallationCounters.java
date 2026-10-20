package io.opaa.ratelimit;

import io.opaa.ratelimit.RateLimitService.Decision;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link InstallationCounters} in {@code installation_counters}: one row per key and window length,
 * written by one upsert per counted event. The row lock of the upsert serialises the instances, and
 * Postgres re-evaluates the condition of a waiting upsert on the row its predecessor left, so a
 * bound is never exceeded by a race. Every point in time comes from {@code clock_timestamp()}
 * inside the statement (ADR-0053, Entscheidung 9).
 *
 * <p>A statement runs in a transaction of its own when the caller holds one - a read-only caller
 * could not write the count, and a rollback of the caller must not take a counted request back.
 */
@Component
public class JdbcInstallationCounters implements InstallationCounters {

  /** Rolled into the current window: the start of the fixed window {@code now} falls into. */
  private static final String NOW_WINDOW =
      "SELECT CAST(? AS varchar) AS counter_key, CAST(? AS integer) AS window_seconds,"
          + " date_bin(make_interval(secs => CAST(? AS integer)), t, TIMESTAMPTZ 'epoch') AS"
          + " window_start, t FROM (SELECT clock_timestamp() AS t) now_";

  /** Whether the stored row still is the window the event falls into. */
  private static final String SAME = "c.window_start >= EXCLUDED.window_start";

  private static final String PREVIOUS =
      "(CASE WHEN "
          + SAME
          + " THEN c.previous_count WHEN c.window_start = EXCLUDED.window_start"
          + " - make_interval(secs => c.window_seconds) THEN c.current_count ELSE 0 END)";

  private static final String CURRENT = "(CASE WHEN " + SAME + " THEN c.current_count ELSE 0 END)";

  private static final String WEIGHT =
      "GREATEST(0, 1 - extract(epoch FROM (EXCLUDED.counted_at - GREATEST(c.window_start,"
          + " EXCLUDED.window_start))) / c.window_seconds)";

  private static final String UPSERT =
      "INSERT INTO installation_counters AS c (counter_key, window_seconds, window_start,"
          + " current_count, previous_count, counted_at) SELECT counter_key, window_seconds,"
          + " window_start, 1, 0, t FROM ("
          + NOW_WINDOW
          + ") n ON CONFLICT (counter_key, window_seconds) DO UPDATE SET window_start ="
          + " GREATEST(c.window_start, EXCLUDED.window_start), previous_count = "
          + PREVIOUS
          + ", current_count = "
          + CURRENT
          + " + 1, counted_at = GREATEST(c.counted_at, EXCLUDED.counted_at)";

  private static final String ESTIMATE_OF_ROW =
      "previous_count * GREATEST(0, 1 - extract(epoch FROM (counted_at - window_start))"
          + " / window_seconds) + current_count";

  private final JdbcOperations jdbc;
  private final TransactionTemplate ownTransaction;

  public JdbcInstallationCounters(
      JdbcOperations jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.ownTransaction = new TransactionTemplate(transactionManager);
    this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public Decision tryCount(String key, Duration window, int max) {
    int seconds = seconds(window);
    List<Timestamp> counted =
        statement(
            () ->
                jdbc.query(
                    UPSERT
                        + " WHERE "
                        + PREVIOUS
                        + " * "
                        + WEIGHT
                        + " + "
                        + CURRENT
                        + " < ? RETURNING c.window_start",
                    (rs, row) -> rs.getTimestamp(1),
                    key,
                    seconds,
                    seconds,
                    max));
    if (!counted.isEmpty()) {
      return Decision.allowAt(counted.getFirst().getTime());
    }
    return Decision.reject(secondsUntilRoom(key, seconds, max));
  }

  @Override
  public void release(String key, Duration window, Decision granted) {
    if (!granted.allowed() || granted.acquiredAtMillis() == 0) {
      return;
    }
    statement(
        () ->
            jdbc.update(
                "UPDATE installation_counters SET current_count = current_count - 1 WHERE"
                    + " counter_key = ? AND window_seconds = ? AND window_start = ? AND"
                    + " current_count > 0",
                key,
                seconds(window),
                new Timestamp(granted.acquiredAtMillis())));
  }

  @Override
  public double count(String key, Duration window) {
    int seconds = seconds(window);
    Double estimate =
        statement(
            () ->
                jdbc.queryForObject(
                    UPSERT + " RETURNING " + ESTIMATE_OF_ROW, Double.class, key, seconds, seconds));
    return estimate == null ? 0 : estimate;
  }

  @Override
  public boolean claimQuietPeriod(String key, Duration window, Duration quietFor) {
    Integer claimed =
        statement(
            () ->
                jdbc.update(
                    "UPDATE installation_counters SET quiet_until = clock_timestamp() +"
                        + " make_interval(secs => CAST(? AS bigint)) WHERE counter_key = ? AND window_seconds = ?"
                        + " AND (quiet_until IS NULL OR quiet_until <= clock_timestamp())",
                    quietFor.toSeconds(),
                    key,
                    seconds(window)));
    return claimed != null && claimed == 1;
  }

  @Override
  public void reset(String key, Duration window) {
    statement(
        () ->
            jdbc.update(
                "DELETE FROM installation_counters WHERE counter_key = ? AND window_seconds = ?",
                key,
                seconds(window)));
  }

  /**
   * The seconds until the estimate drops below {@code max}: the previous window weighs less with
   * every second, and once the current one is full it has to become the previous one first.
   */
  private long secondsUntilRoom(String key, int windowSeconds, int max) {
    List<double[]> rows =
        statement(
            () ->
                jdbc.query(
                    "SELECT previous_count, current_count, extract(epoch FROM (clock_timestamp()"
                        + " - window_start)) FROM installation_counters WHERE counter_key = ? AND"
                        + " window_seconds = ?",
                    (rs, row) -> new double[] {rs.getDouble(1), rs.getDouble(2), rs.getDouble(3)},
                    key,
                    windowSeconds));
    if (rows.isEmpty()) {
      return 1;
    }
    return waitSeconds(
        rows.getFirst()[0], rows.getFirst()[1], rows.getFirst()[2], windowSeconds, max);
  }

  /** See {@link #secondsUntilRoom}; {@code elapsed} is the age of the current window. */
  static long waitSeconds(
      double previous, double current, double elapsed, int windowSeconds, int max) {
    double window = windowSeconds;
    double wait;
    if (current >= max) {
      // the current window becomes the previous one, which then has to weigh below the bound
      wait = Math.max(0, window - elapsed) + window * (1 - max / current);
    } else if (previous > 0) {
      wait = window * (1 - (max - current) / previous) - elapsed;
    } else {
      wait = 0;
    }
    return Math.max(1, (long) Math.ceil(wait));
  }

  private <T> T statement(Supplier<T> work) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      return ownTransaction.execute(status -> work.get());
    }
    return work.get();
  }

  private static int seconds(Duration window) {
    return Math.toIntExact(window.toSeconds());
  }
}
