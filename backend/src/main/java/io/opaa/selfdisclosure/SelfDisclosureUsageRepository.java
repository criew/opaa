package io.opaa.selfdisclosure;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The one row per person in {@code self_disclosure_usage}: the counters of one calendar day, the
 * last result, the time of the last completed export, the reservation of the running export and the
 * hash of an open ticket. Every decision that two instances could take at once is one conditional
 * statement under the row lock, by the database clock (ADR-0053, Entscheidungen 9 and 12); the
 * calendar day is passed in, computed once from the database clock in the zone of the installation.
 */
@Component
class SelfDisclosureUsageRepository {

  /** How long a ticket is valid. */
  static final String TICKET_VALIDITY = SelfDisclosureService.TICKET_SECONDS + " seconds";

  /** How long a reservation lives after its last renewal. */
  static final String LEASE = "2 minutes";

  private final JdbcTemplate jdbc;

  SelfDisclosureUsageRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The row of {@code accountId} with the database clock, or empty when there is none. */
  Optional<Usage> find(UUID accountId) {
    List<Usage> rows =
        jdbc.query(
            "SELECT u.*, clock_timestamp() AS now FROM self_disclosure_usage u"
                + " WHERE u.account_id = ?",
            (rs, i) -> usage(rs),
            accountId);
    return rows.stream().findFirst();
  }

  /**
   * Stores {@code ticketHash} as the one open ticket of the person if no export of hers runs and
   * the day has completed exports and starts left; a newer ticket replaces an older one. {@code
   * true} when stored.
   */
  boolean issueTicket(UUID accountId, LocalDate today, byte[] ticketHash, int dailyLimit) {
    List<UUID> stored =
        jdbc.queryForList(
            "INSERT INTO self_disclosure_usage AS u (account_id, count_day, ticket_hash,"
                + " ticket_expires_at) VALUES (?, ?, ?, clock_timestamp() + interval '"
                + TICKET_VALIDITY
                + "') ON CONFLICT (account_id) DO UPDATE SET ticket_hash = EXCLUDED.ticket_hash,"
                + " ticket_expires_at = EXCLUDED.ticket_expires_at"
                + " WHERE (u.lease_until IS NULL OR u.lease_until < clock_timestamp())"
                + " AND (u.count_day <> EXCLUDED.count_day"
                + " OR (u.completed_count < ? AND u.starts_count < ?))"
                + " RETURNING u.account_id",
            UUID.class,
            accountId,
            Date.valueOf(today),
            ticketHash,
            dailyLimit,
            dailyLimit + SelfDisclosureSettings.EXTRA_STARTS);
    return !stored.isEmpty();
  }

  /**
   * Redeems the ticket exactly once and reserves the export: valid, the self-disclosure switched
   * on, no export running and the day not exhausted. Counts the start for {@code today} and marks a
   * run left behind by a crashed instance as aborted. Returns the person, or empty.
   */
  Optional<UUID> redeem(byte[] ticketHash, UUID runId, LocalDate today) {
    Date day = Date.valueOf(today);
    List<UUID> redeemed =
        jdbc.queryForList(
            "UPDATE self_disclosure_usage u SET ticket_hash = NULL, ticket_expires_at = NULL,"
                + " last_result = CASE WHEN u.run_id IS NOT NULL THEN 'ABORTED'"
                + " ELSE u.last_result END,"
                + " run_id = ?, lease_until = clock_timestamp() + interval '"
                + LEASE
                + "', starts_count = CASE WHEN u.count_day = ? THEN u.starts_count + 1 ELSE 1 END,"
                + " completed_count = CASE WHEN u.count_day = ? THEN u.completed_count ELSE 0 END,"
                + " count_day = ?"
                + " FROM self_disclosure_settings s"
                + " WHERE s.id = 1 AND s.enabled AND u.ticket_hash = ?"
                + " AND u.ticket_expires_at > clock_timestamp()"
                + " AND (u.lease_until IS NULL OR u.lease_until < clock_timestamp())"
                + " AND (u.count_day <> ? OR (u.completed_count < s.daily_limit"
                + " AND u.starts_count < s.daily_limit + "
                + SelfDisclosureSettings.EXTRA_STARTS
                + ")) RETURNING u.account_id",
            UUID.class,
            runId,
            day,
            day,
            day,
            ticketHash,
            day);
    return redeemed.stream().findFirst();
  }

  /** The person of a ticket that is still valid, whether redeemable or not. */
  Optional<UUID> holderOfValidTicket(byte[] ticketHash) {
    return jdbc
        .queryForList(
            "SELECT account_id FROM self_disclosure_usage WHERE ticket_hash = ?"
                + " AND ticket_expires_at > clock_timestamp()",
            UUID.class,
            ticketHash)
        .stream()
        .findFirst();
  }

  /** Extends the reservation of {@code runId}; {@code false} when it is no longer held. */
  boolean renew(UUID accountId, UUID runId) {
    return jdbc.update(
            "UPDATE self_disclosure_usage SET lease_until = clock_timestamp() + interval '"
                + LEASE
                + "' WHERE account_id = ? AND run_id = ?",
            accountId,
            runId)
        == 1;
  }

  /** Counts a completely delivered export for the day it began and frees the reservation. */
  boolean complete(UUID accountId, UUID runId) {
    return jdbc.update(
            "UPDATE self_disclosure_usage SET completed_count = completed_count + 1,"
                + " last_completed_at = clock_timestamp(), last_result = 'COMPLETED',"
                + " run_id = NULL, lease_until = NULL WHERE account_id = ? AND run_id = ?",
            accountId,
            runId)
        == 1;
  }

  /** Marks the export aborted and frees the reservation; consumes nothing. */
  void abort(UUID accountId, UUID runId) {
    jdbc.update(
        "UPDATE self_disclosure_usage SET last_result = 'ABORTED', run_id = NULL,"
            + " lease_until = NULL WHERE account_id = ? AND run_id = ?",
        accountId,
        runId);
  }

  private static Usage usage(ResultSet rs) throws SQLException {
    return new Usage(
        rs.getDate("count_day").toLocalDate(),
        rs.getInt("starts_count"),
        rs.getInt("completed_count"),
        instant(rs, "last_completed_at"),
        rs.getString("last_result"),
        rs.getObject("run_id", UUID.class),
        instant(rs, "lease_until"),
        instant(rs, "now"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  /**
   * One row as read, with {@code now} of the database clock at the read.
   *
   * @param lastResult {@code COMPLETED} or {@code ABORTED}, {@code null} before the first export
   */
  record Usage(
      LocalDate countDay,
      int startsCount,
      int completedCount,
      Instant lastCompletedAt,
      String lastResult,
      UUID runId,
      Instant leaseUntil,
      Instant now) {

    boolean running() {
      return runId != null && leaseUntil != null && leaseUntil.isAfter(now);
    }

    /** The result of the last attempt; a reservation that lapsed counts as aborted. */
    String effectiveLastResult() {
      return runId != null && !running() ? "ABORTED" : lastResult;
    }

    int startsOn(LocalDate day) {
      return day.equals(countDay) ? startsCount : 0;
    }

    int completedOn(LocalDate day) {
      return day.equals(countDay) ? completedCount : 0;
    }
  }
}
