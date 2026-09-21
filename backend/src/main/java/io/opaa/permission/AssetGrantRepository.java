package io.opaa.permission;

import io.opaa.api.types.PermissionSubjectType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetGrantRepository extends JpaRepository<AssetGrant, UUID> {

  /** All grants on one asset, used to populate {@link AssetAccessService}'s per-asset cache. */
  List<AssetGrant> findByAssetTypeAndAssetId(AssetType assetType, UUID assetId);

  /**
   * All grants across every one of {@code assetIds} in a single query - used by {@link
   * AssetAccessService#effectiveRoles} to compute a role for a whole list response without either
   * an N+1 query per asset or the per-asset cache, whose staleness window could otherwise disagree
   * with the fresher, uncached {@link #findReadableAssetIdsByDirectGrant}/{@link
   * #findReadableAssetIdsByGroupGrant} that decide list membership and leave a listed asset with no
   * resolvable role.
   */
  List<AssetGrant> findByAssetTypeAndAssetIdIn(AssetType assetType, Set<UUID> assetIds);

  /**
   * A namespace for {@link #lockAssetGrantsForMutation}'s Postgres advisory locks, arbitrary but
   * fixed and documented so a future, unrelated advisory lock elsewhere in the codebase can pick a
   * different one instead of colliding. Namespaces in use: 202 (this), 203 ({@code
   * UserRepository#TOKEN_ROLE_CHANGE_LOCK_NAMESPACE}), 204 ({@code
   * GroupRepository#IDENTITY_PROVIDER_GROUP_LOCK_NAMESPACE}), 205 ({@code
   * io.opaa.group.sync.DirectorySyncRunLock#DIRECTORY_SYNC_RUN_LOCK_NAMESPACE}).
   */
  int ASSET_GRANT_MUTATION_LOCK_NAMESPACE = 202;

  /**
   * Acquires a transaction-scoped Postgres advisory lock keyed on the asset (namespaced under
   * {@link #ASSET_GRANT_MUTATION_LOCK_NAMESPACE}), serializing every concurrent grant mutation on
   * the same asset - called first, before {@link #countOtherActiveOwnerGrants}, by {@code
   * AssetGrantService}'s last-active-OWNER guard. The lock key mixes the asset type in, so two
   * asset types sharing an id cannot serialize against each other.
   *
   * <p><b>Advisory lock, not {@code SELECT ... FOR UPDATE} on the grant rows:</b> row-locking every
   * grant of the asset directly fixes the entity-staleness bug (see {@link
   * #countOtherActiveOwnerGrants}'s Javadoc) but deadlocks two concurrent mutations on the same
   * asset, each trying to lock every grant row - even with a deterministic {@code ORDER BY id},
   * since Postgres executes the {@code Sort} before the {@code LockRows} step for that query shape.
   * A single advisory lock per asset has only one lock to acquire, removing the whole class of
   * deadlock. Automatically released at transaction end ({@code _xact_}, commit or rollback), so it
   * cannot be leaked like a {@code pg_advisory_lock}/{@code pg_advisory_unlock} pair would risk. A
   * database lock, not an in-process one: with more than one application instance an in-process
   * lock would not serialize concurrent mutations at all.
   */
  @Query(
      value =
          "SELECT 1 FROM (SELECT pg_advisory_xact_lock("
              + ASSET_GRANT_MUTATION_LOCK_NAMESPACE
              + ", hashtext(:assetType || ':' || CAST(:assetId AS text)))) acquired",
      nativeQuery = true)
  int lockAssetGrantsForMutation(
      @Param("assetType") String assetType, @Param("assetId") UUID assetId);

  /**
   * The number of active {@code OWNER} grants on the asset, excluding {@code excludingGrantId} -
   * used only by {@code AssetGrantService}'s last-active-OWNER guard, and only after {@link
   * #lockAssetGrantsForMutation} has been called in the same transaction for the same asset; see
   * that method's Javadoc for why a plain scalar read is safe to rely on once the advisory lock is
   * held.
   *
   * <p><b>Scalar aggregate, not an entity list:</b> {@code AssetGrantService}'s caller resolves the
   * effective role first, which populates {@link AssetAccessService}'s cache via {@link
   * #findByAssetTypeAndAssetId} inside the same transaction - an entity-list read here would hit
   * Hibernate's first-level cache and return those now-possibly-stale managed instances instead of
   * the current row values, letting the guard decide on outdated data. A plain scalar query has no
   * entity identity to resolve against the persistence context, so it always reflects the row
   * values as they stand at the time it runs.
   */
  @Query(
      value =
          "SELECT count(*) FROM asset_grants"
              + " WHERE asset_type = :assetType"
              + "   AND asset_id = :assetId"
              + "   AND role = 'OWNER'"
              + "   AND (expires_at IS NULL OR expires_at > :now)"
              + "   AND id <> :excludingGrantId",
      nativeQuery = true)
  long countOtherActiveOwnerGrants(
      @Param("assetType") String assetType,
      @Param("assetId") UUID assetId,
      @Param("excludingGrantId") UUID excludingGrantId,
      @Param("now") Instant now);

  Optional<AssetGrant> findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectUserId(
      AssetType assetType, UUID assetId, PermissionSubjectType subjectType, UUID subjectUserId);

  Optional<AssetGrant> findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectGroupId(
      AssetType assetType, UUID assetId, PermissionSubjectType subjectType, UUID subjectGroupId);

  /**
   * Every asset id of {@code assetType} the given user can reach via a direct grant, not expired as
   * of {@code now}. Only used as one branch of {@link AssetAccessService#readableAssetIds} -
   * deliberately not cached (unlike the per-asset grant cache used for single-asset checks): the
   * vector search filter must reflect a revoked grant on the very next query, and a single indexed
   * query is simpler and safer than a second cache-invalidation path to keep correct.
   */
  @Query(
      "select g.assetId from AssetGrant g "
          + "where g.assetType = :assetType "
          + "and g.subjectType = io.opaa.api.types.PermissionSubjectType.USER "
          + "and g.subjectUserId = :userId and g.organizationId = :organizationId "
          + "and (g.expiresAt is null or g.expiresAt > :now)")
  Set<UUID> findReadableAssetIdsByDirectGrant(
      @Param("assetType") AssetType assetType,
      @Param("userId") UUID userId,
      @Param("organizationId") UUID organizationId,
      @Param("now") Instant now);

  /** The group-grant counterpart of {@link #findReadableAssetIdsByDirectGrant}. */
  @Query(
      "select g.assetId from AssetGrant g "
          + "where g.assetType = :assetType "
          + "and g.subjectType = io.opaa.api.types.PermissionSubjectType.GROUP "
          + "and g.subjectGroupId in :groupIds and g.organizationId = :organizationId "
          + "and (g.expiresAt is null or g.expiresAt > :now)")
  Set<UUID> findReadableAssetIdsByGroupGrant(
      @Param("assetType") AssetType assetType,
      @Param("groupIds") Set<UUID> groupIds,
      @Param("organizationId") UUID organizationId,
      @Param("now") Instant now);

  /**
   * Every non-expired group grant of one organization on {@code assetType}, for {@link
   * AssetAccessService#grantedAssetIdsByGroup} - one query for every profile at once instead of one
   * per profile, which would put a round trip per row onto the administration page's profile picker
   * (#1053).
   */
  @Query(
      "select g from AssetGrant g "
          + "where g.assetType = :assetType "
          + "and g.subjectType = io.opaa.api.types.PermissionSubjectType.GROUP "
          + "and g.organizationId = :organizationId "
          + "and (g.expiresAt is null or g.expiresAt > :now)")
  List<AssetGrant> findActiveGroupGrants(
      @Param("assetType") AssetType assetType,
      @Param("organizationId") UUID organizationId,
      @Param("now") Instant now);

  /**
   * Whether the given group is the subject of any grant, on any asset - used by {@code
   * GroupService#deleteGroup} to reject deleting a group that still holds a grant ({@code
   * fk_asset_grants_subject_group_organization} is RESTRICT, so without this check the delete is
   * refused by the constraint alone, with the generic foreign-key message and without naming what
   * still holds the group). Deliberately a second, independent check next to {@link
   * AssetOwnershipDirectory} (ownership), not a replacement for it - a group can be both the owner
   * of an asset and hold a grant on an unrelated one; deleting it must be rejected for either
   * reason.
   */
  boolean existsBySubjectGroupId(UUID subjectGroupId);

  /** How many grants one group holds - the count a transfer checks before it loads them (#1834). */
  long countBySubjectGroupId(UUID subjectGroupId);

  /**
   * Every grant the given groups hold, expired ones included - what deleting an identity provider
   * counts to decide whether its groups still have an effect (ADR-0036, Entscheidung 2). An expired
   * grant counts too: its row still blocks the RESTRICT key, and it is still a right someone has to
   * decide about.
   */
  List<AssetGrant> findBySubjectGroupIdIn(Collection<UUID> subjectGroupIds);
}
