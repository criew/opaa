package io.opaa.auth;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
   * matched in - the same boundary {@code GroupMembershipRepository} enforces for group reads.
   * Matches on {@code subject} alone (not {@code subject} + {@code issuer}): the MVP runs a single
   * OIDC issuer per organization (see {@code AuthProperties}), and requiring an issuer here would
   * force the directory sync to carry issuer configuration that duplicates what auth already knows.
   */
  List<User> findByOrganizationIdAndSubjectIn(UUID organizationId, Collection<String> subjects);

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
