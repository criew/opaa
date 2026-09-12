package io.opaa.auth;

import io.opaa.api.types.SystemRole;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

  /**
   * Namespace of {@link #lockRoleChanges}'s advisory locks - see {@code
   * AssetGrantRepository#ASSET_GRANT_MUTATION_LOCK_NAMESPACE} for the list every namespace is
   * registered in.
   */
  int TOKEN_ROLE_CHANGE_LOCK_NAMESPACE = 203;

  Optional<User> findBySubjectAndIssuer(String subject, String issuer);

  /**
   * The local sign-in's lookup (ADR-0033, Entscheidung 1): the address compared without regard to
   * case, but only among the accounts of {@code issuer} - the partial unique index {@code
   * ux_users_local_email} guarantees at most one row for the local issuer, an OIDC account with the
   * same address is never found here.
   */
  Optional<User> findByIssuerAndEmailIgnoreCase(String issuer, String email);

  /**
   * How many accounts were provisioned through {@code issuer} - what {@code
   * io.opaa.auth.oidc.OidcProviderService} refuses to cut off by changing a provider's issuer
   * (ADR-0025, Entscheidung 2).
   */
  long countByIssuer(String issuer);

  /**
   * Used by {@code AuditIncidentScopeService#request} (#393 code review, finding 8) to reject an
   * anlassbezogene Klärung named against a person outside the requester's own organization before a
   * grant row - and later, at query time, a pseudonym - is ever created for them.
   */
  Optional<User> findByIdAndOrganizationId(UUID id, UUID organizationId);

  /**
   * Resolves directory group members to their {@link User} rows for #237's directory
   * synchronisation, scoped to the organization so a subject from another tenant can never be
   * matched in - the same boundary {@code GroupMembershipRepository} enforces for group reads - and
   * to {@code issuer}, the trusted provider's (ADR-0025, Entscheidung 4): a second provider's
   * account with the same subject must never inherit the directory's memberships.
   */
  List<User> findByOrganizationIdAndIssuerAndSubjectIn(
      UUID organizationId, String issuer, Collection<String> subjects);

  /**
   * Serializes the changes that could remove the last login-capable administrator of one
   * organization for the rest of the transaction ({@code
   * io.opaa.auth.local.LocalAdminAvailabilityGuard}, ADR-0033 Entscheidung 4): the "does another
   * administrator remain?" count is only sound when no second change counts this one's row as still
   * remaining.
   */
  @Query(
      value =
          "SELECT 1 FROM (SELECT pg_advisory_xact_lock("
              + TOKEN_ROLE_CHANGE_LOCK_NAMESPACE
              + ", hashtext(CAST(:organizationId AS text)))) acquired",
      nativeQuery = true)
  int lockRoleChanges(@Param("organizationId") UUID organizationId);

  /**
   * The administrators the {@code LocalAdminAvailabilityGuard} counts - filtered for login
   * capability in Java, with the same rule the login applies.
   */
  List<User> findByOrganizationIdAndSystemRole(UUID organizationId, SystemRole systemRole);

  /**
   * The regular accounts of {@code issuer} - what switching the local account management off ends
   * the sessions of (ADR-0033, Entscheidung 4); system administrators keep theirs.
   */
  List<User> findByIssuerAndSystemRoleNot(String issuer, SystemRole systemRole);

  /** The local accounts of one organization - what the local account list is built from (#1537). */
  List<User> findByOrganizationIdAndIssuer(UUID organizationId, String issuer);

  /** Every account of {@code issuer} - what the daily local-account run walks (#1537). */
  List<User> findByIssuer(String issuer);

  /**
   * Per-table counts of the rows that reference the user through an {@code ON DELETE RESTRICT}
   * foreign key - what deleting a local account has to be clear of (#1537). One statement, so the
   * refusal can name the reason in the log without a query per table.
   */
  @Query(
      value =
          "SELECT"
              + " (SELECT count(*) FROM knowledge_libraries WHERE owner_user_id = :id) AS libraries,"
              + " (SELECT count(*) FROM spaces WHERE owner_id = :id AND is_default = false) AS spaces,"
              + " (SELECT count(*) FROM chats WHERE author_id = :id) AS chats,"
              + " (SELECT count(*) FROM group_membership_history WHERE user_id = :id) AS groupHistory,"
              + " (SELECT count(*) FROM asset_grant_history WHERE subject_user_id = :id) AS grantHistory,"
              + " (SELECT count(*) FROM asset_grants WHERE subject_user_id = :id"
              + "   OR granted_by_user_id = :id) AS grants,"
              + " (SELECT count(*) FROM space_asset_associations WHERE created_by_user_id = :id)"
              + "   AS associations,"
              + " (SELECT count(*) FROM audit_incident_scope_grants WHERE requested_by_user_id = :id"
              + "   OR approved_by_user_id = :id OR subject_user_id = :id) AS incidentScopes,"
              + " (SELECT count(*) FROM diagnostic_impersonation_grants WHERE granted_by_user_id = :id"
              + "   OR revoked_by_user_id = :id) AS impersonationGrants",
      nativeQuery = true)
  DeletionBlockers countDeletionBlockers(@Param("id") UUID userId);

  /** The result of {@link #countDeletionBlockers}; every non-zero count refuses the deletion. */
  interface DeletionBlockers {
    long getLibraries();

    long getSpaces();

    long getChats();

    long getGroupHistory();

    long getGrantHistory();

    long getGrants();

    long getAssociations();

    long getIncidentScopes();

    long getImpersonationGrants();
  }

  /** Writes {@code role} only while the stored role is still {@code expected}. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update User u set u.systemRole = :role where u.id = :id and u.systemRole = :expected")
  int changeRoleIfStill(
      @Param("id") UUID id, @Param("expected") SystemRole expected, @Param("role") SystemRole role);

  /**
   * Used by {@code AdminController#listUsers} (#271) to scope the user list to the caller's own
   * organization - {@code findAll()} used to return every organization's users, including to a
   * SYSTEM_ADMIN, whose reach must stop at their own organization's boundary just like every other
   * role (#199).
   */
  List<User> findByOrganizationId(UUID organizationId);

  /**
   * Used by {@code UserSearchController#listUsers} (#777, capped and query-gated after #778 review
   * finding 4) - unlike {@link #findByOrganizationId(UUID)} above (still unbounded, but only ever
   * reached by the {@code SYSTEM_ADMIN}-only admin list), this backs an endpoint every
   * authenticated organization member can call, so it is deliberately never allowed to return the
   * whole organization: {@code pageable} caps the row count and the caller (see {@link
   * UserService#searchInOrganization}) never invokes this without a query that already passed the
   * minimum-length check. Matches case-insensitively against both displayName and email so a caller
   * can find someone by either.
   */
  @Query(
      "SELECT u FROM User u WHERE u.organizationId = :organizationId AND "
          + "(LOWER(u.displayName) LIKE LOWER(CONCAT('%', :query, '%')) "
          + "OR LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')))")
  List<User> searchByOrganizationId(
      @Param("organizationId") UUID organizationId,
      @Param("query") String query,
      Pageable pageable);
}
