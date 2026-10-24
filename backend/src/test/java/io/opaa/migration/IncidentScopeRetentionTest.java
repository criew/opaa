package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The persons a grant of the anlassbezogene Klärung names: deleting one of their accounts keeps the
 * person in the grant as the protocol's pseudonym instead of being refused, a grant never loses a
 * person it names, and a requester never approves their own grant.
 */
class IncidentScopeRetentionTest extends AbstractBaselineTest {

  private UUID requester;
  private UUID approver;
  private UUID subject;
  private UUID approvedGrant;
  private UUID pendingGrant;

  @BeforeEach
  void grantsNamingThreePersons() throws Exception {
    requester = insertUser();
    approver = insertUser();
    subject = insertUser();
    approvedGrant = insertGrant(subject, requester, approver, "2025-06-01T10:00:00Z");
    insertGrant(subject, requester, approver, "2025-09-01T10:00:00Z");
    insertGrant(subject, requester, approver, "2026-02-01T10:00:00Z");
    pendingGrant = insertGrant(subject, requester, null, null);
  }

  @Test
  void deletingAnAccountAGrantNamesKeepsThePersonAsTheProtocolPseudonym() throws Exception {
    UUID pseudonym = UUID.randomUUID();
    execute(
        "INSERT INTO audit_actor_pseudonyms (pseudonym_id, user_id, organization_id) VALUES ('"
            + pseudonym
            + "', '"
            + subject
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "')");

    execute("DELETE FROM users WHERE id = '" + subject + "'");
    execute("DELETE FROM users WHERE id = '" + requester + "'");
    execute("DELETE FROM users WHERE id = '" + approver + "'");

    assertThat(countWhere("audit_incident_scope_grants", "subject_user_id IS NOT NULL")).isZero();
    assertThat(stringOf(column("subject_pseudonym_id", approvedGrant)))
        .isEqualTo(pseudonym.toString());
    assertThat(stringOf(column("subject_pseudonym_id", pendingGrant)))
        .isEqualTo(pseudonym.toString());
    String requesterPseudonym = stringOf(column("requested_by_pseudonym_id", approvedGrant));
    assertThat(requesterPseudonym).isNotNull().isNotEqualTo(pseudonym.toString());
    // one person, one pseudonym across every grant naming them
    assertThat(
            countWhere(
                "audit_incident_scope_grants",
                "requested_by_pseudonym_id IS DISTINCT FROM '" + requesterPseudonym + "'"))
        .isZero();
    assertThat(stringOf(column("approved_by_pseudonym_id", approvedGrant))).isNotNull();
    assertThat(stringOf(column("approved_by_pseudonym_id", pendingGrant))).isNull();
    assertThat(countRows("audit_actor_pseudonyms")).isZero();
  }

  @Test
  void aGrantNeverLosesThePersonItNames() {
    assertRejected(
        "UPDATE audit_incident_scope_grants SET subject_user_id = NULL WHERE id = '"
            + approvedGrant
            + "'",
        "chk_audit_incident_scope_grants_persons_named");
    assertRejected(
        "UPDATE audit_incident_scope_grants SET approved_by_user_id = NULL WHERE id = '"
            + approvedGrant
            + "'",
        "chk_audit_incident_scope_grants_persons_named");
  }

  @Test
  void selfApprovalIsStillRejected() {
    assertRejected(
        "UPDATE audit_incident_scope_grants SET approved_by_user_id = requested_by_user_id"
            + " WHERE id = '"
            + approvedGrant
            + "'",
        "chk_audit_incident_scope_grants_requester");
  }

  private static String column(String column, UUID grant) {
    return "SELECT " + column + " FROM audit_incident_scope_grants WHERE id = '" + grant + "'";
  }

  private UUID insertGrant(UUID subject, UUID requester, UUID approver, String approvedAt)
      throws Exception {
    UUID id = UUID.randomUUID();
    boolean approved = approver != null;
    execute(
        "INSERT INTO audit_incident_scope_grants (id, organization_id, subject_user_id,"
            + " scope_start, scope_end, purpose, reason, requested_by_user_id, requested_at,"
            + " approved_by_user_id, approved_at, usable_until, status) VALUES ('"
            + id
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', '"
            + subject
            + "', '2025-01-01T00:00:00Z', '2025-01-31T00:00:00Z', 'SECURITY_INCIDENT',"
            + " 'Verdacht', '"
            + requester
            + "', '2025-01-01T00:00:00Z', "
            + quoted(approver)
            + ", "
            + quoted(approvedAt)
            + ", "
            + (approved ? "timestamptz '" + approvedAt + "' + interval '30 days'" : "NULL")
            + ", '"
            + (approved ? "APPROVED" : "PENDING")
            + "')");
    return id;
  }
}
