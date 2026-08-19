package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Applies Liquibase changelog 021 in isolation against a database built from the real, versioned
 * changelog through changeSet 020 - the same pattern as {@code Migration020UploadMetadataTest},
 * with {@code test-master-through-020.yaml} as the pre-migration fixture.
 *
 * <p>#393 code review, finding 5: the PR body cited {@code
 * chk_audit_incident_scope_grants_requester} (Vier-Augen-Prinzip enforced at the database level,
 * not only in {@code AuditIncidentScopeGrant#approve}) as a second line of defence, but nothing
 * exercised it directly - {@code AuditIncidentScopeServiceIntegrationTest} goes exclusively through
 * the entity method, which already throws first. This class inserts/updates the table directly, the
 * way an application-level bug (or a bypass of {@code AuditIncidentScopeGrant} entirely) would, and
 * proves every constraint the changeSet's own comment claims: the requester/approver pairing and
 * inequality, the paired-nullable {@code approved_*}/{@code usable_until} columns, {@code
 * scope_start <= scope_end}, both closed value lists, and the {@code RESTRICT} foreign key on
 * {@code subject_user_id}.
 */
@Testcontainers(disabledWithoutDocker = true)
class Migration021AuditIncidentScopeGrantsTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-020.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);

    applyChangelog021();
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void aValidGrantWithTwoDifferentPeopleCanBeInsertedAndApproved() throws Exception {
    UUID requester = insertUser();
    UUID approver = insertUser();
    UUID subject = insertUser();
    UUID grantId = insertPendingGrant(requester, subject);

    approveGrant(grantId, approver);

    assertThat(status(grantId)).isEqualTo("APPROVED");
  }

  @Test
  void selfApprovalIsRejectedAtTheDatabaseLevel() throws Exception {
    UUID requester = insertUser();
    UUID subject = insertUser();
    UUID grantId = insertPendingGrant(requester, subject);

    assertThatThrownBy(() -> approveGrant(grantId, requester))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_audit_incident_scope_grants_requester");
  }

  @Test
  void anApprovedByWithoutAnApprovedAtIsRejected() throws Exception {
    UUID requester = insertUser();
    UUID approver = insertUser();
    UUID subject = insertUser();
    UUID grantId = insertPendingGrant(requester, subject);

    assertThatThrownBy(
            () ->
                exec(
                    "UPDATE audit_incident_scope_grants SET approved_by_user_id = '"
                        + approver
                        + "', status = 'APPROVED' WHERE id = '"
                        + grantId
                        + "'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_audit_incident_scope_grants_requester");
  }

  @Test
  void scopeStartAfterScopeEndIsRejectedAtTheDatabaseLevel() throws Exception {
    UUID requester = insertUser();
    UUID subject = insertUser();
    Instant now = Instant.now();

    assertThatThrownBy(
            () ->
                insertGrant(
                    UUID.randomUUID(),
                    requester,
                    subject,
                    now.plus(1, ChronoUnit.DAYS),
                    now,
                    "SECURITY_INCIDENT",
                    "PENDING"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_audit_incident_scope_grants_scope");
  }

  @Test
  void aPurposeOutsideTheClosedListIsRejected() throws Exception {
    UUID requester = insertUser();
    UUID subject = insertUser();
    Instant now = Instant.now();

    assertThatThrownBy(
            () ->
                insertGrant(
                    UUID.randomUUID(),
                    requester,
                    subject,
                    now,
                    now.plus(1, ChronoUnit.DAYS),
                    "PERFORMANCE_REVIEW",
                    "PENDING"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_audit_incident_scope_grants_purpose");
  }

  @Test
  void aStatusOutsideTheClosedListIsRejected() throws Exception {
    UUID requester = insertUser();
    UUID subject = insertUser();
    Instant now = Instant.now();

    assertThatThrownBy(
            () ->
                insertGrant(
                    UUID.randomUUID(),
                    requester,
                    subject,
                    now,
                    now.plus(1, ChronoUnit.DAYS),
                    "SECURITY_INCIDENT",
                    "REJECTED"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_audit_incident_scope_grants_status");
  }

  @Test
  void deletingASubjectUserReferencedByAGrantIsRestricted() throws Exception {
    UUID requester = insertUser();
    UUID subject = insertUser();
    insertPendingGrant(requester, subject);

    assertThatThrownBy(() -> exec("DELETE FROM users WHERE id = '" + subject + "'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("fk_audit_incident_scope_grants_subject");
  }

  private void applyChangelog021() throws Exception {
    applyChangelog(connection, "db/changelog/changes/021-audit-incident-scope-grants.yaml");
  }

  private UUID insertUser() throws SQLException {
    UUID id = UUID.randomUUID();
    exec(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at,"
            + " organization_id) VALUES ('"
            + id
            + "', 'subject-"
            + id
            + "', 'issuer', 'user@example.com', 'User', now(), '"
            + SEEDED_ORGANIZATION_ID
            + "')");
    return id;
  }

  private UUID insertPendingGrant(UUID requester, UUID subject) throws SQLException {
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    insertGrant(
        id, requester, subject, now, now.plus(1, ChronoUnit.DAYS), "SECURITY_INCIDENT", "PENDING");
    return id;
  }

  private void insertGrant(
      UUID id,
      UUID requester,
      UUID subject,
      Instant scopeStart,
      Instant scopeEnd,
      String purpose,
      String status)
      throws SQLException {
    exec(
        "INSERT INTO audit_incident_scope_grants (id, organization_id, subject_user_id,"
            + " scope_start, scope_end, purpose, reason, requested_by_user_id, requested_at,"
            + " status) VALUES ('"
            + id
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', '"
            + subject
            + "', '"
            + scopeStart
            + "', '"
            + scopeEnd
            + "', '"
            + purpose
            + "', 'Testanlass', '"
            + requester
            + "', now(), '"
            + status
            + "')");
  }

  private void approveGrant(UUID grantId, UUID approver) throws SQLException {
    exec(
        "UPDATE audit_incident_scope_grants SET approved_by_user_id = '"
            + approver
            + "', approved_at = now(), usable_until = now() + interval '30 days',"
            + " status = 'APPROVED' WHERE id = '"
            + grantId
            + "'");
  }

  private String status(UUID grantId) throws SQLException {
    try (Statement statement = connection.createStatement();
        var result =
            statement.executeQuery(
                "SELECT status FROM audit_incident_scope_grants WHERE id = '" + grantId + "'")) {
      result.next();
      return result.getString(1);
    }
  }

  private void exec(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
