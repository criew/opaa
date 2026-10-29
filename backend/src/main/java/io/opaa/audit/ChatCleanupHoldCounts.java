package io.opaa.audit;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * How often the deletion of the chats of a locked account was held, per calendar month, for the
 * report to the staff council: the count outlives the hold and names no person and no account.
 */
@Repository
public class ChatCleanupHoldCounts {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  ChatCleanupHoldCounts(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** Counts one hold placed now in {@code organizationId}, in the caller's transaction. */
  public void countHold(UUID organizationId) {
    jdbc.update(
        "INSERT INTO audit_chat_cleanup_hold_monthly_counts"
            + " (organization_id, hold_month, hold_count) VALUES (?, ?, 1)"
            + " ON CONFLICT (organization_id, hold_month)"
            + " DO UPDATE SET hold_count = audit_chat_cleanup_hold_monthly_counts.hold_count + 1",
        organizationId,
        LocalDate.now(clock).withDayOfMonth(1));
  }

  /** The holds of the months {@code fromMonth} to {@code toMonth}, both first days of a month. */
  long sum(UUID organizationId, LocalDate fromMonth, LocalDate toMonth) {
    Long total =
        jdbc.queryForObject(
            "SELECT coalesce(sum(hold_count), 0) FROM audit_chat_cleanup_hold_monthly_counts"
                + " WHERE organization_id = ? AND hold_month BETWEEN ? AND ?",
            Long.class,
            organizationId,
            fromMonth,
            toMonth);
    return total == null ? 0 : total;
  }
}
