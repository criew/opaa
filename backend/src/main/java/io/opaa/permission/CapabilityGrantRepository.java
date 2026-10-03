package io.opaa.permission;

import io.opaa.api.types.Capability;
import io.opaa.api.types.CapabilitySubjectType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CapabilityGrantRepository extends JpaRepository<CapabilityGrant, UUID> {

  /**
   * The capabilities the account reaches without any group of its own: those granted to every
   * account of the organization, and those granted to the account itself. Split from {@link
   * #findCapabilitiesGrantedToGroups} so a caller with no group memberships never has to pass an
   * empty {@code in} list - the same split {@code AssetAccessService#readableAssetIds} makes.
   */
  @Query(
      "select distinct g.capability from CapabilityGrant g "
          + "where g.organizationId = :organizationId "
          + "and ((g.subjectType = io.opaa.api.types.CapabilitySubjectType.ALL_ACCOUNTS) "
          + "  or (g.subjectType = io.opaa.api.types.CapabilitySubjectType.USER "
          + "      and g.subjectUserId = :userId))")
  Set<Capability> findCapabilitiesGrantedDirectly(
      @Param("organizationId") UUID organizationId, @Param("userId") UUID userId);

  /** The group half of {@link #findCapabilitiesGrantedDirectly}; never called with an empty set. */
  @Query(
      "select distinct g.capability from CapabilityGrant g "
          + "where g.organizationId = :organizationId "
          + "and g.subjectType = io.opaa.api.types.CapabilitySubjectType.GROUP "
          + "and g.subjectGroupId in :groupIds")
  Set<Capability> findCapabilitiesGrantedToGroups(
      @Param("organizationId") UUID organizationId, @Param("groupIds") Collection<UUID> groupIds);

  /**
   * The scopes of {@code capability} the account reaches without any group of its own - the scoped
   * counterpart of {@link #findCapabilitiesGrantedDirectly}.
   */
  @Query(
      "select distinct g.scope from CapabilityGrant g "
          + "where g.organizationId = :organizationId and g.capability = :capability "
          + "and ((g.subjectType = io.opaa.api.types.CapabilitySubjectType.ALL_ACCOUNTS) "
          + "  or (g.subjectType = io.opaa.api.types.CapabilitySubjectType.USER "
          + "      and g.subjectUserId = :userId))")
  Set<String> findScopesGrantedDirectly(
      @Param("organizationId") UUID organizationId,
      @Param("capability") Capability capability,
      @Param("userId") UUID userId);

  /** The group half of {@link #findScopesGrantedDirectly}; never called with an empty set. */
  @Query(
      "select distinct g.scope from CapabilityGrant g "
          + "where g.organizationId = :organizationId and g.capability = :capability "
          + "and g.subjectType = io.opaa.api.types.CapabilitySubjectType.GROUP "
          + "and g.subjectGroupId in :groupIds")
  Set<String> findScopesGrantedToGroups(
      @Param("organizationId") UUID organizationId,
      @Param("capability") Capability capability,
      @Param("groupIds") Collection<UUID> groupIds);

  /** Every grant of one capability in one scope - what removing the scope withdraws. */
  List<CapabilityGrant> findByOrganizationIdAndCapabilityAndScope(
      UUID organizationId, Capability capability, String scope);

  /** Every grant of one organization, for the administration overview. */
  List<CapabilityGrant> findByOrganizationId(UUID organizationId);

  Optional<CapabilityGrant> findByIdAndOrganizationId(UUID id, UUID organizationId);

  /**
   * The grant of {@code capability} in {@code scope} to one subject, if any; {@code scope} {@code
   * null} matches the unscoped grant. One query for the three subject kinds, {@code subjectId}
   * {@code null} for {@code ALL_ACCOUNTS}.
   */
  @Query(
      "select g from CapabilityGrant g where g.organizationId = :organizationId"
          + " and g.capability = :capability and g.subjectType = :subjectType"
          + " and ((:scope is null and g.scope is null) or g.scope = :scope)"
          + " and (g.subjectType = io.opaa.api.types.CapabilitySubjectType.ALL_ACCOUNTS"
          + "  or g.subjectUserId = :subjectId or g.subjectGroupId = :subjectId)")
  Optional<CapabilityGrant> findGrant(
      @Param("organizationId") UUID organizationId,
      @Param("capability") Capability capability,
      @Param("scope") String scope,
      @Param("subjectType") CapabilitySubjectType subjectType,
      @Param("subjectId") UUID subjectId);

  /**
   * Whether the given group holds any capability - read by {@code GroupService#deleteGroup} before
   * deleting a group, for the same reason as {@code AssetGrantRepository#existsBySubjectGroupId}:
   * {@code fk_capability_grants_subject_group_organization} is RESTRICT. Without the check the
   * refusal still arrives as a {@code 409} ({@code GlobalExceptionHandler} maps a foreign-key
   * violation there), but with the generic "Der Datensatz wird noch verwendet" instead of the
   * reason - and the caller cannot tell an Anlegerecht from an asset grant or an ownership.
   */
  boolean existsBySubjectGroupId(UUID subjectGroupId);

  /**
   * The subset of {@code groupIds} that holds a capability - what deleting an identity provider
   * counts as an effect of its groups (ADR-0036, Entscheidung 2), next to their asset grants and
   * ownerships. Never called with an empty set.
   */
  @Query(
      "select distinct g.subjectGroupId from CapabilityGrant g where g.subjectGroupId in :groupIds")
  List<UUID> findSubjectGroupIdsIn(@Param("groupIds") Collection<UUID> groupIds);

  /**
   * How many capabilities each of {@code groupIds} holds, in one grouped query - what the overview
   * "wo wirkt diese Gruppe" (#1821) asks for every group at once. A group holding none is absent.
   */
  @Query(
      "select g.subjectGroupId as subjectGroupId, count(g) as capabilityCount from CapabilityGrant"
          + " g where g.subjectGroupId in :groupIds group by g.subjectGroupId")
  List<SubjectGroupCapabilityCount> countBySubjectGroupIdIn(
      @Param("groupIds") Collection<UUID> groupIds);

  interface SubjectGroupCapabilityCount {
    UUID getSubjectGroupId();

    long getCapabilityCount();
  }

  /** Every capability one group holds - what a transfer moves to the target group (#1834). */
  List<CapabilityGrant> findBySubjectGroupId(UUID subjectGroupId);

  /** The same figure without the rows, for a transfer's work limit. */
  long countBySubjectGroupId(UUID subjectGroupId);
}
