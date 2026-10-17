package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code identity/2026-10-17-incident-scope-monthly-counts.yaml} on an installation that already
 * has grants of the anlassbezogene Klärung and their yearly case count: every grant is counted once
 * as requested in the month of its request, an approved one also as approved in the month of its
 * approval and as ended in the month its usability ends, and the yearly count goes. A yearly count
 * the grants no longer explain stops the migration instead of being lost.
 */
class IncidentScopeMonthlyCountsMigrationTest extends AbstractBaselineTest {

  private static final String FILE =
      "db/changelog/identity/2026-10-17-incident-scope-monthly-counts.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  private UUID requester;
  private UUID approver;
  private UUID subject;

  @BeforeEach
  void createPersons() throws Exception {
    requester = insertUser();
    approver = insertUser();
    subject = insertUser();
  }

  @Test
  void theGrantsAlreadyThereAreCountedPerMonthPurposeAndTransition() throws Exception {
    insertGrant("SECURITY_INCIDENT", "2026-03-05T10:00:00Z", "2026-03-10T10:00:00Z");
    insertGrant("SECURITY_INCIDENT", "2026-03-20T10:00:00Z", "2026-04-02T10:00:00Z");
    insertGrant("SECURITY_INCIDENT", "2026-04-01T10:00:00Z", null);
    insertGrant("DATA_BREACH_INVESTIGATION", "2026-02-27T10:00:00Z", "2026-02-28T10:00:00Z");
    execute(
        "INSERT INTO audit_incident_scope_case_counts (organization_id, case_year, purpose,"
            + " case_count) VALUES ('"
            + SEEDED_ORGANIZATION_ID
            + "', 2026, 'SECURITY_INCIDENT', 2), ('"
            + SEEDED_ORGANIZATION_ID
            + "', 2026, 'DATA_BREACH_INVESTIGATION', 1)");

    applyChangelog(connection, FILE);

    assertThat(counts())
        .containsExactlyInAnyOrder(
            "2026-03-01 SECURITY_INCIDENT REQUESTED 2",
            "2026-04-01 SECURITY_INCIDENT REQUESTED 1",
            "2026-03-01 SECURITY_INCIDENT APPROVED 1",
            "2026-04-01 SECURITY_INCIDENT APPROVED 1",
            "2026-04-01 SECURITY_INCIDENT EXPIRED 1",
            "2026-05-01 SECURITY_INCIDENT EXPIRED 1",
            "2026-02-01 DATA_BREACH_INVESTIGATION REQUESTED 1",
            "2026-02-01 DATA_BREACH_INVESTIGATION APPROVED 1",
            "2026-03-01 DATA_BREACH_INVESTIGATION EXPIRED 1");
    assertThat(booleanOf("SELECT to_regclass('audit_incident_scope_case_counts') IS NULL"))
        .as("the yearly count is gone")
        .isTrue();
  }

  @Test
  void aYearlyCountTheGrantsNoLongerExplainStopsTheMigration() throws Exception {
    insertGrant("SECURITY_INCIDENT", "2026-03-05T10:00:00Z", "2026-03-10T10:00:00Z");
    execute(
        "INSERT INTO audit_incident_scope_case_counts (organization_id, case_year, purpose,"
            + " case_count) VALUES ('"
            + SEEDED_ORGANIZATION_ID
            + "', 2026, 'SECURITY_INCIDENT', 2)");

    assertThatThrownBy(() -> applyChangelog(connection, FILE))
        .hasStackTraceContaining("audit_incident_scope_case_counts");
  }

  @Test
  void aMonthIsAlwaysItsFirstDayAndACountNeverZero() throws Exception {
    applyChangelog(connection, FILE);

    assertRejected(
        "INSERT INTO audit_incident_scope_monthly_counts (organization_id, case_month, purpose,"
            + " transition, case_count) VALUES ('"
            + SEEDED_ORGANIZATION_ID
            + "', '2026-03-15', 'SECURITY_INCIDENT', 'REQUESTED', 1)",
        "chk_audit_incident_scope_monthly_counts_month");
    assertRejected(
        "INSERT INTO audit_incident_scope_monthly_counts (organization_id, case_month, purpose,"
            + " transition, case_count) VALUES ('"
            + SEEDED_ORGANIZATION_ID
            + "', '2026-03-01', 'SECURITY_INCIDENT', 'REQUESTED', 0)",
        "chk_audit_incident_scope_monthly_counts_count");
    assertRejected(
        "INSERT INTO audit_incident_scope_monthly_counts (organization_id, case_month, purpose,"
            + " transition, case_count) VALUES ('"
            + SEEDED_ORGANIZATION_ID
            + "', '2026-03-01', 'SECURITY_INCIDENT', 'REJECTED', 1)",
        "chk_audit_incident_scope_monthly_counts_transition");
  }

  private List<String> counts() throws Exception {
    List<String> rows = new ArrayList<>();
    try (var statement = connection.createStatement();
        var result =
            statement.executeQuery(
                "SELECT case_month, purpose, transition, case_count"
                    + " FROM audit_incident_scope_monthly_counts")) {
      while (result.next()) {
        rows.add(
            result.getString(1)
                + " "
                + result.getString(2)
                + " "
                + result.getString(3)
                + " "
                + result.getInt(4));
      }
    }
    return rows;
  }

  private void insertGrant(String purpose, String requestedAt, String approvedAt) throws Exception {
    boolean approved = approvedAt != null;
    execute(
        "INSERT INTO audit_incident_scope_grants (id, organization_id, subject_user_id,"
            + " scope_start, scope_end, purpose, reason, requested_by_user_id, requested_at,"
            + " approved_by_user_id, approved_at, usable_until, status) VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', '"
            + subject
            + "', '2025-01-01T00:00:00Z', '2025-01-31T00:00:00Z', '"
            + purpose
            + "', 'Verdacht', '"
            + requester
            + "', '"
            + requestedAt
            + "', "
            + (approved ? quoted(approver) : "NULL")
            + ", "
            + quoted(approvedAt)
            + ", "
            + (approved ? "timestamptz '" + approvedAt + "' + interval '30 days'" : "NULL")
            + ", '"
            + (approved ? "APPROVED" : "PENDING")
            + "')");
  }
}
