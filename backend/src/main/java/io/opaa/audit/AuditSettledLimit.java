package io.opaa.audit;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The limit below which every entry of {@code audit_log} is settled (ADR-0055, Entscheidung 5): the
 * start of the oldest open transaction of another client session, at most now minus a small delay.
 * {@code recorded_at} is stamped in the transaction that inserts the entry, so nothing committing
 * later lies below the limit read before the entries. Shared by the SIEM export and the checksum
 * chain; requires the primary, not a replica.
 */
final class AuditSettledLimit {

  private final JdbcTemplate jdbc;
  private final Duration settleDelay;

  AuditSettledLimit(JdbcTemplate jdbc, Duration settleDelay) {
    this.jdbc = jdbc;
    this.settleDelay = settleDelay;
  }

  Limit read() {
    return jdbc.queryForObject(
        "SELECT clock_timestamp() AS now, LEAST(clock_timestamp() - (? * interval '1 millisecond'),"
            + " coalesce(oldest.xact_start, clock_timestamp())) AS settled, oldest.pid,"
            + " oldest.application_name FROM (SELECT 1) AS one LEFT JOIN LATERAL"
            + " (SELECT xact_start, pid, application_name FROM pg_stat_activity"
            + " WHERE datname = current_database() AND pid <> pg_backend_pid()"
            + " AND backend_type = 'client backend' AND xact_start IS NOT NULL"
            + " ORDER BY xact_start LIMIT 1) AS oldest ON true",
        (rs, row) ->
            new Limit(
                rs.getTimestamp("now").toInstant().truncatedTo(ChronoUnit.MICROS),
                rs.getTimestamp("settled").toInstant(),
                rs.getObject("pid", Integer.class),
                rs.getString("application_name")),
        settleDelay.toMillis());
  }

  /** The database clock, the settled limit and the session of the oldest open transaction. */
  record Limit(Instant now, Instant settled, Integer oldestPid, String oldestApplication) {}
}
