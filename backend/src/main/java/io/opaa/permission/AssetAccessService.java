package io.opaa.permission;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.PermissionSubjectType;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The one rights formula over {@link AssetGrant}s, for an asset of any {@link AssetType}: the
 * highest role a user reaches on an asset - directly or through one of their groups - and the set
 * of asset ids they reach at all (see
 * docs/features/spaces-and-assets.md#rechte-an-einem-asset-erhalten). Whatever holds for only one
 * asset type is composed on top by that type's own service, which is why this class takes a {@code
 * floor} instead of knowing about a library's organization-wide visibility ({@code
 * io.opaa.library.LibraryAccessService}).
 *
 * <p>Two access paths, deliberately not unified:
 *
 * <ul>
 *   <li>{@link #effectiveRole} is a single-asset lookup on the hot path of every CRUD request and
 *       is cached per asset, invalidated after commit whenever a grant on that asset changes -
 *       mirroring {@link GroupMembershipResolver}'s cache and invalidation pattern.
 *   <li>{@link #readableAssetIds}, backing the permission-aware vector search filter, is
 *       deliberately <b>not</b> cached: it is a single indexed query per call, so a revoked grant
 *       takes effect on the very next query without depending on a second cache-invalidation path
 *       being correct - the search filter is where a stale cache would leak data, not merely delay
 *       a UI refresh.
 * </ul>
 *
 * <p><b>No system-admin bypass anywhere in this class.</b> An administrative floor is a decision of
 * the asset type's own service, and only for administration - never for the search (ADR-0036,
 * Entscheidung 1).
 */
@Component
public class AssetAccessService {

  private final AssetGrantRepository grantRepository;
  private final GroupMembershipResolver membershipResolver;
  private final Cache<AssetKey, List<AssetGrant>> grantsByAsset;

  public AssetAccessService(
      AssetGrantRepository grantRepository, GroupMembershipResolver membershipResolver) {
    this.grantRepository = grantRepository;
    this.membershipResolver = membershipResolver;
    // Same reasoning as GroupMembershipResolver#groupIdsByUser: a stale entry only ever grants
    // access a moment too long between a completed transaction's invalidation and the next read,
    // never too little - invalidateAsset below, called post-commit, is the primary correctness
    // mechanism. Time-based expiry is a safety net only.
    this.grantsByAsset =
        Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(Duration.ofMinutes(10)).build();
  }

  /** The cache key: an asset id alone is not unique across asset types. */
  private record AssetKey(AssetType assetType, UUID assetId) {}

  /**
   * The highest {@link AssetRole} {@code userId} holds on the asset, or {@code null} if none.
   *
   * @param floor the role the asset grants without any grant at all (a library's organization-wide
   *     visibility), or {@code null} when nothing but a grant reaches it.
   */
  public AssetRole effectiveRole(AssetType assetType, UUID assetId, UUID userId, AssetRole floor) {
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(userId);
    return bestRole(cachedGrants(assetType, assetId), userId, groupIds, Instant.now(), floor);
  }

  /**
   * The effective {@link AssetRole} for every one of {@code assetIds}, for {@code userId} - the
   * list counterpart of {@link #effectiveRole}, deliberately not built by calling that method once
   * per asset:
   *
   * <ul>
   *   <li><b>Correctness:</b> list membership comes from {@link #readableAssetIds}, which is
   *       deliberately uncached so a just-granted or just-revoked right is reflected immediately,
   *       while {@link #effectiveRole} reads the separately cached per-asset grants, invalidated
   *       only after commit. This method instead reads every grant for {@code assetIds} in one
   *       query, giving the same freshness guarantee.
   *   <li><b>Performance:</b> one query for N assets instead of up to N queries on a cold cache.
   * </ul>
   *
   * @param floors the per-asset floor (see {@link #effectiveRole}); an asset absent from the map
   *     has no floor.
   * @return one entry per requested id; {@code null} value where no grant and no floor reaches the
   *     user.
   */
  public Map<UUID, AssetRole> effectiveRoles(
      AssetType assetType, Set<UUID> assetIds, UUID userId, Map<UUID, AssetRole> floors) {
    Instant now = Instant.now();
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(userId);
    Map<UUID, List<AssetGrant>> grantsByAssetId =
        grantRepository.findByAssetTypeAndAssetIdIn(assetType, assetIds).stream()
            .collect(Collectors.groupingBy(AssetGrant::getAssetId));

    Map<UUID, AssetRole> roles = new HashMap<>();
    for (UUID assetId : assetIds) {
      roles.put(
          assetId,
          bestRole(
              grantsByAssetId.getOrDefault(assetId, List.of()),
              userId,
              groupIds,
              now,
              floors.get(assetId)));
    }
    return roles;
  }

  /**
   * Every asset id of {@code assetType} in {@code organizationId} that {@code userId} reaches
   * through a grant: direct grants plus grants to the groups the user currently belongs to.
   * Whatever an asset type adds without a grant is unioned in by that type's own service.
   */
  public Set<UUID> readableAssetIds(AssetType assetType, UUID userId, UUID organizationId) {
    Instant now = Instant.now();
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(userId);

    Set<UUID> readable =
        new HashSet<>(
            grantRepository.findReadableAssetIdsByDirectGrant(
                assetType, userId, organizationId, now));
    if (!groupIds.isEmpty()) {
      readable.addAll(
          grantRepository.findReadableAssetIdsByGroupGrant(
              assetType, groupIds, organizationId, now));
    }
    return readable;
  }

  /**
   * Every asset id of {@code assetType} a single group holds a non-expired grant on - the
   * group-shaped counterpart of {@link #readableAssetIds}, deliberately without the direct user
   * grants that formula also considers: a permission profile is a role, not a person (#1053).
   * Uncached for the same reason {@link #readableAssetIds} is.
   */
  public Set<UUID> grantedAssetIdsForGroup(AssetType assetType, UUID groupId, UUID organizationId) {
    return new HashSet<>(
        grantRepository.findReadableAssetIdsByGroupGrant(
            assetType, Set.of(groupId), organizationId, Instant.now()));
  }

  /**
   * The same answer as {@link #grantedAssetIdsForGroup} for every group of one organization at
   * once, in a single query rather than one per group - what the administration's profile picker
   * needs to show a count per profile without a round trip per row (#1053). A group with no grant
   * is absent from the result.
   */
  public Map<UUID, Set<UUID>> grantedAssetIdsByGroup(AssetType assetType, UUID organizationId) {
    Map<UUID, Set<UUID>> grantedByGroup = new HashMap<>();
    for (AssetGrant grant :
        grantRepository.findActiveGroupGrants(assetType, organizationId, Instant.now())) {
      grantedByGroup
          .computeIfAbsent(grant.getSubjectGroupId(), id -> new HashSet<>())
          .add(grant.getAssetId());
    }
    return grantedByGroup;
  }

  /**
   * Whether {@code userId} holds {@link AssetRole#OWNER} on the asset on a basis they did not
   * create for themselves: an unexpired {@code OWNER} grant somebody else issued, or one they
   * issued to themselves while being the asset's named owner ({@code namedOwner}, which the asset
   * type decides). There is no administrative floor here, so an {@code OWNER} grant an
   * administrator issued to themselves through such a floor does not count - closing the two-step
   * path "grant myself OWNER via the administrative floor, then act as the responsible owner".
   * {@link AssetGrant#updateRole} carries the changer into {@code grantedByUserId} on a role change
   * <em>and</em> on the revival of an expired grant, so both raising a pre-existing foreign grant
   * to {@code OWNER} and re-arming an expired foreign {@code OWNER} grant at an unchanged role
   * count as self-issued - the expiry filter below is what makes the second case necessary.
   *
   * @param groupIds the caller's groups, as {@link #groupIdsForUser} returned them to whoever
   *     decided {@code namedOwner} - passed in rather than resolved again, so an invalidation
   *     between the two reads cannot pair a stale {@code namedOwner} with a fresh reach and fail
   *     open.
   */
  public boolean holdsIndependentOwnerRole(
      AssetType assetType, UUID assetId, UUID userId, Set<UUID> groupIds, boolean namedOwner) {
    Instant now = Instant.now();
    return cachedGrants(assetType, assetId).stream()
        .filter(grant -> !grant.isExpired(now))
        .filter(grant -> grant.getRole() == AssetRole.OWNER)
        .filter(grant -> reaches(grant, userId, groupIds))
        .anyMatch(grant -> namedOwner || !userId.equals(grant.getGrantedByUserId()));
  }

  /**
   * Evicts the cached grant list for an asset, called after commit (or rollback) whenever one of
   * its grants changes - see {@code AssetGrantService#invalidateAfterCommit} for why "after commit"
   * rather than inline.
   */
  public void invalidateAsset(AssetType assetType, UUID assetId) {
    grantsByAsset.invalidate(new AssetKey(assetType, assetId));
  }

  /** The groups {@code userId} belongs to - the one derivation every caller shares. */
  public Set<UUID> groupIdsForUser(UUID userId) {
    return membershipResolver.groupIdsForUser(userId);
  }

  private List<AssetGrant> cachedGrants(AssetType assetType, UUID assetId) {
    return grantsByAsset.get(
        new AssetKey(assetType, assetId),
        key -> grantRepository.findByAssetTypeAndAssetId(key.assetType(), key.assetId()));
  }

  /**
   * The single rights-resolution formula both {@link #effectiveRole} and {@link #effectiveRoles}
   * apply, over two different grant sources (a single cached asset's grants vs. a batch-loaded map
   * across many), so the formula itself cannot drift between the two call sites. Highest role among
   * {@code seed} (the caller's starting floor, or {@code null} for none) and every non-expired
   * grant in {@code grants} that reaches {@code userId} - directly, or via one of {@code groupIds}.
   */
  private static AssetRole bestRole(
      Collection<AssetGrant> grants, UUID userId, Set<UUID> groupIds, Instant now, AssetRole seed) {
    AssetRole best = seed;
    for (AssetGrant grant : grants) {
      if (grant.isExpired(now)) {
        continue;
      }
      if (reaches(grant, userId, groupIds) && (best == null || grant.getRole().atLeast(best))) {
        best = grant.getRole();
      }
    }
    return best;
  }

  /** Whether {@code grant} reaches {@code userId} - directly, or via one of {@code groupIds}. */
  private static boolean reaches(AssetGrant grant, UUID userId, Set<UUID> groupIds) {
    return (grant.getSubjectType() == PermissionSubjectType.USER
            && grant.getSubjectUserId().equals(userId))
        || (grant.getSubjectType() == PermissionSubjectType.GROUP
            && groupIds.contains(grant.getSubjectGroupId()));
  }
}
