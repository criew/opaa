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
  Optional<User> findBySubjectAndIssuer(String subject, String issuer);

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
   * Serializes token-derived role changes of one organization for the rest of the transaction
   * ({@code TokenRoleSynchronizer}): the "does another administrator remain?" condition below is
   * only sound when no second withdrawal counts this one's row as still remaining.
   */
  @Query(
      value =
          "SELECT 1 FROM (SELECT pg_advisory_xact_lock(1330, hashtext(CAST(:organizationId AS"
              + " text)))) acquired",
      nativeQuery = true)
  int lockRoleChanges(@Param("organizationId") UUID organizationId);

  /**
   * Writes {@code role} over {@code SYSTEM_ADMIN} only while another {@code SYSTEM_ADMIN} of the
   * same organization remains; {@code 0} means the account is the last one and keeps the role.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update User u set u.systemRole = :role where u.id = :id"
          + " and u.systemRole = io.opaa.api.types.SystemRole.SYSTEM_ADMIN"
          + " and exists (select o.id from User o where o.organizationId = u.organizationId"
          + " and o.systemRole = io.opaa.api.types.SystemRole.SYSTEM_ADMIN and o.id <> u.id)")
  int withdrawSystemAdminIfAnotherRemains(@Param("id") UUID id, @Param("role") SystemRole role);

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
