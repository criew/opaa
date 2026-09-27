package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Invariants of the baseline's workspace changeSet: space memberships of persons and groups
 * (ADR-0036, Entscheidung 6), their history, the association of assets with spaces, and the grants
 * of the diagnosis "Sicht als" (ADR-0016). The protocol of those diagnoses has its own class,
 * {@link DiagnosticContextPrivilegeModelTest}.
 */
class WorkspaceBaselineTest extends AbstractBaselineTest {

  // ---------------------------------------------------------------------------------------------
  // Memberships
  // ---------------------------------------------------------------------------------------------

  /** A membership names exactly one subject - a person or a group. */
  @Test
  void aMembershipNamesExactlyOnePersonOrGroup() throws SQLException {
    UUID space = insertSpace(insertUser());
    UUID user = insertUser();
    UUID group = insertInternalGroup();
    execute(membershipSql(space, "USER", user, null, null));
    execute(membershipSql(space, "GROUP", null, group, 23));

    assertRejected(
        membershipSql(space, "USER", insertUser(), insertInternalGroup(), null),
        "chk_space_memberships_subject");
    assertRejected(
        membershipSql(space, "GROUP", null, null, null), "chk_space_memberships_subject");
    assertRejected(
        membershipSql(space, "GROUP", insertUser(), null, null), "chk_space_memberships_subject");
  }

  /**
   * ADR-0036, Entscheidung 9: the size of a group at the moment it was admitted, never a person's.
   */
  @Test
  void onlyAGroupMembershipCarriesANonNegativeMemberCount() throws SQLException {
    UUID space = insertSpace(insertUser());

    assertRejected(
        membershipSql(space, "USER", insertUser(), null, 1), "chk_space_memberships_member_count");
    assertRejected(
        membershipSql(space, "GROUP", null, insertInternalGroup(), -1),
        "chk_space_memberships_member_count");
  }

  @Test
  void aSubjectHoldsAtMostOneMembershipPerSpace() throws SQLException {
    UUID space = insertSpace(insertUser());
    UUID user = insertUser();
    UUID group = insertInternalGroup();
    execute(membershipSql(space, "USER", user, null, null));
    execute(membershipSql(space, "GROUP", null, group, null));

    assertRejected(
        membershipSql(space, "USER", user, null, null), "uk_space_memberships_user_subject");
    assertRejected(
        membershipSql(space, "GROUP", null, group, null), "uk_space_memberships_group_subject");
    execute(membershipSql(insertSpace(insertUser()), "USER", user, null, null));
  }

  /**
   * A group that still holds a right does not vanish silently, and a group of another organization
   * cannot become a member at all.
   */
  @Test
  void aMemberGroupCannotBeDeletedAndMustBelongToTheSpacesOrganization() throws SQLException {
    UUID space = insertSpace(insertUser());
    UUID group = insertInternalGroup();
    execute(membershipSql(space, "GROUP", null, group, null));

    assertRejected(
        "DELETE FROM groups WHERE id = '" + group + "'", "fk_space_memberships_group_organization");

    UUID otherOrganization = insertOrganization();
    UUID foreignGroup = UUID.randomUUID();
    execute(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES ('"
            + foreignGroup
            + "', '"
            + otherOrganization
            + "', 'AD_HOC', 'Fremd')");
    assertRejected(
        membershipSql(space, "GROUP", null, foreignGroup, null),
        "fk_space_memberships_group_organization");
  }

  // ---------------------------------------------------------------------------------------------
  // Membership history (ADR-0016, ADR-0036 Entscheidung 8)
  // ---------------------------------------------------------------------------------------------

  /**
   * One open interval per subject and space; a zero-length marker beside it records a removal as an
   * event of its own; the subject is exactly one person or group.
   */
  @Test
  void theMembershipHistoryHoldsOneOpenIntervalPerSubjectAndSpaceBesideZeroLengthMarkers()
      throws SQLException {
    UUID space = UUID.randomUUID();
    UUID user = insertUser();
    execute(historySql(space, "USER", user, null, "ADDED", null));
    execute(historySql(space, "USER", user, null, "REMOVED", "now()"));

    assertRejected(
        historySql(space, "USER", user, null, "ADDED", null),
        "uk_space_membership_history_open_user");
    assertRejected(
        historySql(space, "USER", user, insertInternalGroup(), "ADDED", null),
        "chk_space_membership_history_subject");
    assertRejected(
        historySql(space, "GROUP", null, null, "ADDED", null),
        "chk_space_membership_history_subject");
    assertRejected(
        historySql(space, "USER", insertUser(), null, "JOINED", null),
        "chk_space_membership_history_cause");
  }

  /**
   * The object column carries no foreign key - the history outlives its space - while a person in
   * it stays undeletable.
   */
  @Test
  void theMembershipHistoryOutlivesItsSpaceAndHoldsItsPersons() throws SQLException {
    UUID user = insertUser();
    UUID space = insertSpace(insertUser());
    execute(historySql(space, "USER", user, null, "ADDED", null));

    execute("DELETE FROM spaces WHERE id = '" + space + "'");
    assertThat(countWhere("space_membership_history", "space_id = '" + space + "'")).isEqualTo(1);
    assertRejected(
        "DELETE FROM users WHERE id = '" + user + "'",
        "fk_space_membership_history_subject_user_organization");
  }

  // ---------------------------------------------------------------------------------------------
  // Asset associations
  // ---------------------------------------------------------------------------------------------

  /**
   * An association points at an existing asset of the organization, once per space, and goes with
   * it.
   */
  @Test
  void anAssociationNeedsItsAssetOncePerSpaceAndFollowsItOutOfExistence() throws SQLException {
    UUID owner = insertUser();
    UUID space = insertSpace(owner);
    UUID asset = insertAsset("PROMPT_LIBRARY", owner);
    execute(associationSql(space, asset, owner));

    assertRejected(associationSql(space, asset, owner), "uk_space_asset_associations_space_asset");
    assertRejected(
        associationSql(space, UUID.randomUUID(), owner),
        "fk_space_asset_associations_asset_organization");
    execute("DELETE FROM assets WHERE id = '" + asset + "'");
    assertThat(countRows("space_asset_associations")).isZero();
  }

  // ---------------------------------------------------------------------------------------------
  // Diagnostic impersonation grants (ADR-0016, Nachtrag)
  // ---------------------------------------------------------------------------------------------

  @Test
  void aDiagnosticImpersonationGrantNeedsBothAScopeAndAnEnd() throws SQLException {
    UUID holder = insertUser();
    UUID scope = insertInternalGroup();

    assertRejected(impersonationSql(holder, holder, null, "12 months"), "scope_group_id");
    assertRejected(impersonationSql(holder, holder, scope, null), "valid_until");
  }

  @Test
  void aDiagnosticImpersonationGrantIsBoundedToTwelveMonthsAndANonEmptyWindow()
      throws SQLException {
    UUID holder = insertUser();
    UUID scope = insertInternalGroup();
    execute(impersonationSql(holder, holder, scope, "12 months"));

    assertRejected(
        impersonationSql(holder, holder, scope, "13 months"),
        "chk_diagnostic_impersonation_grants_validity");
    assertRejected(
        impersonationSql(holder, holder, scope, "0 months"),
        "chk_diagnostic_impersonation_grants_validity");
  }

  @Test
  void aHalfRecordedRevocationOfADiagnosticImpersonationGrantIsRejected() throws SQLException {
    UUID holder = insertUser();
    execute(impersonationSql(holder, holder, insertInternalGroup(), "6 months"));

    assertRejected(
        "UPDATE diagnostic_impersonation_grants SET revoked_at = now()",
        "chk_diagnostic_impersonation_grants_revocation");
  }

  /**
   * One delete rule for the whole Befugnis: once an account or the scope group involved in it is
   * gone, so is the grant - through the holder, the granter, the revoker and the scope alike.
   */
  @Test
  void aDiagnosticImpersonationGrantGoesWithEveryAccountAndGroupItNames() throws SQLException {
    UUID granter = insertUser();
    execute(impersonationSql(insertUser(), granter, insertInternalGroup(), "6 months"));
    UUID holder = insertUser();
    execute(impersonationSql(holder, insertUser(), insertInternalGroup(), "6 months"));
    UUID scope = insertInternalGroup();
    execute(impersonationSql(insertUser(), insertUser(), scope, "6 months"));
    UUID revoker = insertUser();
    UUID revoked = UUID.randomUUID();
    execute(
        impersonationSql(revoked, insertUser(), insertUser(), insertInternalGroup(), "6 months"));
    execute(
        "UPDATE diagnostic_impersonation_grants SET revoked_at = now(), revoked_by_user_id = '"
            + revoker
            + "' WHERE id = '"
            + revoked
            + "'");

    execute(
        "DELETE FROM users WHERE id IN ('" + granter + "', '" + holder + "', '" + revoker + "')");
    execute("DELETE FROM groups WHERE id = '" + scope + "'");

    assertThat(countRows("diagnostic_impersonation_grants")).isZero();
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private static String membershipSql(
      UUID space, String subjectType, UUID user, UUID group, Integer memberCount) {
    return "INSERT INTO space_memberships (id, space_id, organization_id, role, subject_type,"
        + " user_id, group_id, member_count_at_grant) VALUES (gen_random_uuid(), '"
        + space
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', 'MEMBER', '"
        + subjectType
        + "', "
        + quoted(user)
        + ", "
        + quoted(group)
        + ", "
        + (memberCount == null ? "NULL" : memberCount)
        + ")";
  }

  private static String historySql(
      UUID space, String subjectType, UUID user, UUID group, String cause, String validToLiteral) {
    return "INSERT INTO space_membership_history (id, space_id, organization_id, subject_type,"
        + " subject_user_id, subject_group_id, role, cause, valid_from, valid_to) VALUES"
        + " (gen_random_uuid(), '"
        + space
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + subjectType
        + "', "
        + quoted(user)
        + ", "
        + quoted(group)
        + ", 'MEMBER', '"
        + cause
        + "', now(), "
        + (validToLiteral == null ? "NULL" : validToLiteral)
        + ")";
  }

  private static String associationSql(UUID space, UUID asset, UUID createdBy) {
    return "INSERT INTO space_asset_associations (id, space_id, asset_id, organization_id,"
        + " created_by_user_id) VALUES (gen_random_uuid(), '"
        + space
        + "', '"
        + asset
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + createdBy
        + "')";
  }

  /** {@code duration} is a PostgreSQL interval literal; {@code null} leaves the end open. */
  private static String impersonationSql(
      UUID holder, UUID grantedBy, UUID scopeGroup, String duration) {
    return impersonationSql(UUID.randomUUID(), holder, grantedBy, scopeGroup, duration);
  }

  private static String impersonationSql(
      UUID id, UUID holder, UUID grantedBy, UUID scopeGroup, String duration) {
    return "INSERT INTO diagnostic_impersonation_grants (id, organization_id, holder_user_id,"
        + " scope_group_id, valid_from, valid_until, granted_by_user_id, granted_at) VALUES ('"
        + id
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + holder
        + "', "
        + quoted(scopeGroup)
        + ", now(), "
        + (duration == null ? "NULL" : "now() + interval '" + duration + "'")
        + ", '"
        + grantedBy
        + "', now())";
  }
}
