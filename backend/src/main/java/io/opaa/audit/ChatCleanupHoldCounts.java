package io.opaa.audit;

import io.opaa.api.types.ChatCleanupHoldPurpose;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * How often the deletion of the chats of a locked account was held, and the steps of the holds by
 * purpose, per calendar month, for the report to the staff council: the counts outlive the holds
 * and name no person, no account and no reason.
 */
@Repository
public class ChatCleanupHoldCounts {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  ChatCleanupHoldCounts(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * Counts one step of a hold now in {@code organizationId}, in the caller's transaction; an
   * approval of a new hold also counts in the total of the holds that took effect.
   */
  public void count(
      UUID organizationId, ChatCleanupHoldPurpose purpose, ChatCleanupHoldTransition transition) {
    LocalDate month = LocalDate.now(clock).withDayOfMonth(1);
    jdbc.update(
        "INSERT INTO audit_chat_cleanup_hold_purpose_counts"
            + " (organization_id, case_month, purpose, transition, case_count)"
            + " VALUES (?, ?, ?, ?, 1)"
            + " ON CONFLICT (organization_id, case_month, purpose, transition) DO UPDATE"
            + " SET case_count = audit_chat_cleanup_hold_purpose_counts.case_count + 1",
        organizationId,
        month,
        purpose.name(),
        transition.name());
    if (transition == ChatCleanupHoldTransition.APPROVED) {
      jdbc.update(
          "INSERT INTO audit_chat_cleanup_hold_monthly_counts"
              + " (organization_id, hold_month, hold_count) VALUES (?, ?, 1)"
              + " ON CONFLICT (organization_id, hold_month)"
              + " DO UPDATE SET hold_count = audit_chat_cleanup_hold_monthly_counts.hold_count + 1",
          organizationId,
          month);
    }
  }

  /** The holds that took effect in the months {@code fromMonth} to {@code toMonth}. */
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

  /** The steps of {@code purpose} in the months {@code fromMonth} to {@code toMonth}. */
  Map<ChatCleanupHoldTransition, Long> sumByTransition(
      UUID organizationId, ChatCleanupHoldPurpose purpose, LocalDate fromMonth, LocalDate toMonth) {
    Map<ChatCleanupHoldTransition, Long> totals = new EnumMap<>(ChatCleanupHoldTransition.class);
    for (ChatCleanupHoldTransition transition : ChatCleanupHoldTransition.values()) {
      totals.put(transition, 0L);
    }
    jdbc.query(
        "SELECT transition, sum(case_count) AS total FROM audit_chat_cleanup_hold_purpose_counts"
            + " WHERE organization_id = ? AND purpose = ? AND case_month BETWEEN ? AND ?"
            + " GROUP BY transition",
        rs -> {
          totals.put(
              ChatCleanupHoldTransition.valueOf(rs.getString("transition")), rs.getLong("total"));
        },
        organizationId,
        purpose.name(),
        fromMonth,
        toMonth);
    return totals;
  }
}
