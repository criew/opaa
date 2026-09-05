package io.opaa.group;

import io.opaa.api.types.GroupKind;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupRepository extends JpaRepository<Group, UUID> {

  List<Group> findByOrganizationId(UUID organizationId);

  /**
   * Same as {@link #findByOrganizationId}, but with memberships eagerly fetched - required whenever
   * the caller reads {@code getMemberships()} outside the read transaction (e.g. to compute {@code
   * memberCount} in a response mapper called from the controller, with {@code open-in-view:
   * false}); otherwise a {@code LazyInitializationException} surfaces as an unhandled 500.
   */
  @Query(
      "select distinct g from Group g left join fetch g.memberships "
          + "where g.organizationId = :organizationId")
  List<Group> findByOrganizationIdWithMemberships(@Param("organizationId") UUID organizationId);

  /** Same fetch-join reasoning as {@link #findByOrganizationIdWithMemberships}, by id set. */
  @Query("select distinct g from Group g left join fetch g.memberships where g.id in :groupIds")
  List<Group> findAllByIdWithMemberships(@Param("groupIds") Iterable<UUID> groupIds);

  @Query("select distinct g from Group g left join fetch g.memberships where g.id = :groupId")
  Optional<Group> findByIdWithMemberships(@Param("groupId") UUID groupId);

  /**
   * All {@link GroupKind#ORG_UNIT} groups of an organization, with their memberships eagerly
   * fetched - what {@link io.opaa.group.sync.DirectorySyncService} diffs the directory's snapshot
   * against. {@link GroupKind#AD_HOC} groups are never touched by synchronisation and are excluded
   * here rather than filtered by the caller.
   */
  @Query(
      "select distinct g from Group g left join fetch g.memberships "
          + "where g.organizationId = :organizationId and g.kind = io.opaa.api.types.GroupKind.ORG_UNIT")
  List<Group> findByOrganizationIdAndKindOrgUnit(@Param("organizationId") UUID organizationId);

  /**
   * The external ids of the {@link GroupKind#IDENTITY_PROVIDER} groups a user is a member of within
   * one provider's namespace ({@code prefix} = {@code oidc:<provider-id>:}) - the one read {@code
   * TokenGroupSynchronizer} pays per request to tell "nothing changed" from "resync".
   */
  @Query(
      "select g.externalId from GroupMembership m join m.group g"
          + " where m.userId = :userId and g.kind = io.opaa.api.types.GroupKind.IDENTITY_PROVIDER"
          + " and g.externalId like concat(:prefix, '%')")
  Set<String> findIdentityProviderExternalIdsOfUser(
      @Param("userId") UUID userId, @Param("prefix") String prefix);

  Optional<Group> findByOrganizationIdAndKindAndExternalId(
      UUID organizationId, GroupKind kind, String externalId);

  /**
   * Serializes {@code TokenGroupSynchronizer}'s writes for one provider for the rest of the
   * transaction, so two first sign-ins naming the same new group create it once.
   */
  @Query(
      value =
          "SELECT 1 FROM (SELECT pg_advisory_xact_lock(1331, hashtext(CAST(:providerId AS text))))"
              + " acquired",
      nativeQuery = true)
  int lockIdentityProviderGroups(@Param("providerId") UUID providerId);
}
