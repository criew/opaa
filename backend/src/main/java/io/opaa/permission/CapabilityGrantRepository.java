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

  /** Every grant of one organization, for the administration overview. */
  List<CapabilityGrant> findByOrganizationId(UUID organizationId);

  Optional<CapabilityGrant> findByIdAndOrganizationId(UUID id, UUID organizationId);

  Optional<CapabilityGrant> findByOrganizationIdAndCapabilityAndSubjectTypeAndSubjectUserId(
      UUID organizationId,
      Capability capability,
      CapabilitySubjectType subjectType,
      UUID subjectUserId);

  Optional<CapabilityGrant> findByOrganizationIdAndCapabilityAndSubjectTypeAndSubjectGroupId(
      UUID organizationId,
      Capability capability,
      CapabilitySubjectType subjectType,
      UUID subjectGroupId);

  Optional<CapabilityGrant> findByOrganizationIdAndCapabilityAndSubjectType(
      UUID organizationId, Capability capability, CapabilitySubjectType subjectType);

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
}
