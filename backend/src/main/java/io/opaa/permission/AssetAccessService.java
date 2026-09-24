package io.opaa.permission;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.api.types.AccessBasis;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.PermissionSubjectType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The one rights formula for an asset of any {@link AssetType}: the highest role a user reaches on
 * an asset and the set of asset ids they may read at all - through a direct grant, a grant to one
 * of their groups, or the asset's organization-wide release (see
 * docs/features/spaces-and-assets.md#rechte-an-einem-asset-erhalten). The release is read from the
 * asset shell ({@code assets.visibility}); for a single asset the caller, who has loaded it, states
 * it as {@code organizationWide}, and this class decides what it confers.
 *
 * <p>Two access paths, deliberately not unified:
 *
 * <ul>
 *   <li>{@link #effectiveRole} is a single-asset lookup on the hot path of every CRUD request and
 *       is cached per asset, invalidated after commit whenever a grant on that asset changes -
 *       mirroring {@link GroupMembershipResolver}'s cache and invalidation pattern.
 *   <li>{@link #readableAssetIds}, backing the permission-aware vector search filter, is
 *       deliberately <b>not</b> cached: it is a single indexed query per source, so a revoked grant
 *       takes effect on the very next query without depending on a second cache-invalidation path
 *       being correct - the search filter is where a stale cache would leak data, not merely delay
 *       a UI refresh.
 * </ul>
 *
 * <p><b>No system-admin bypass anywhere in this class.</b> The administrative floor belongs to the
 * asset administration ({@code io.opaa.asset}), and only to administration - never to the search
 * (ADR-0036, Entscheidung 1).
 */
@Component
public class AssetAccessService {

  private final AssetGrantRepository grantRepository;
  private final GroupMembershipResolver membershipResolver;
  private final GroupSubjectDirectory groupDirectory;
  private final Cache<AssetKey, List<AssetGrant>> grantsByAsset;

  public AssetAccessService(
      AssetGrantRepository grantRepository,
      GroupMembershipResolver membershipResolver,
      GroupSubjectDirectory groupDirectory) {
    this.grantRepository = grantRepository;
    this.membershipResolver = membershipResolver;
    this.groupDirectory = groupDirectory;
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
   * @param organizationWide whether the asset is released organization-wide, as the caller read it
   *     off the loaded asset - such an asset confers {@link AssetRole#VIEWER} without any grant.
   */
  public AssetRole effectiveRole(
      AssetType assetType, UUID assetId, UUID userId, boolean organizationWide) {
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(userId);
    return bestRole(
        cachedGrants(assetType, assetId),
        userId,
        groupIds,
        Instant.now(),
        organizationWideFloor(organizationWide));
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
   * @param organizationWideIds those of {@code assetIds} released organization-wide.
   * @return one entry per requested id; {@code null} value where nothing reaches the user.
   */
  public Map<UUID, AssetRole> effectiveRoles(
      AssetType assetType, Set<UUID> assetIds, UUID userId, Set<UUID> organizationWideIds) {
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
              organizationWideFloor(organizationWideIds.contains(assetId))));
    }
    return roles;
  }

  /**
   * Every asset id of {@code assetType} in {@code organizationId} that {@code userId} may read:
   * direct grants, grants to the groups the user currently belongs to, and every asset released
   * organization-wide. Space associations appear nowhere in this formula.
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
    readable.addAll(organizationWideAssetIds(assetType, organizationId));
    return readable;
  }

  /**
   * Every asset id of {@code assetType} a <b>permission profile</b> may read: the group's own
   * non-expired grants plus every asset released organization-wide - the group-shaped counterpart
   * of {@link #readableAssetIds}, deliberately without the direct user grants: a profile is a
   * group, not a person (#1053, ADR-0036 Entscheidung 7). Uncached for the same reason.
   */
  public Set<UUID> readableAssetIdsForGroup(
      AssetType assetType, UUID groupId, UUID organizationId) {
    Set<UUID> readable =
        new HashSet<>(
            grantRepository.findReadableAssetIdsByGroupGrant(
                assetType, Set.of(groupId), organizationId, Instant.now()));
    readable.addAll(organizationWideAssetIds(assetType, organizationId));
    return readable;
  }

  /**
   * How many assets of {@code assetType} each of {@code groupIds} may read, by the formula of
   * {@link #readableAssetIdsForGroup} - in two queries for the whole set rather than two per group,
   * which the administration's profile picker would otherwise pay per row (#1053). Every requested
   * id gets an entry, including a group without any grant.
   */
  public Map<UUID, Integer> readableAssetCountsForGroups(
      AssetType assetType, Collection<UUID> groupIds, UUID organizationId) {
    Set<UUID> organizationWide = organizationWideAssetIds(assetType, organizationId);
    Map<UUID, Set<UUID>> grantedByGroup = new HashMap<>();
    for (AssetGrant grant :
        grantRepository.findActiveGroupGrants(assetType, organizationId, Instant.now())) {
      grantedByGroup
          .computeIfAbsent(grant.getSubjectGroupId(), id -> new HashSet<>())
          .add(grant.getAssetId());
    }
    Map<UUID, Integer> counts = new HashMap<>();
    for (UUID groupId : groupIds) {
      Set<UUID> readable = new HashSet<>(organizationWide);
      readable.addAll(grantedByGroup.getOrDefault(groupId, Set.of()));
      counts.put(groupId, readable.size());
    }
    return counts;
  }

  /**
   * Whether {@code userId} holds {@link AssetRole#OWNER} on the asset on a basis they did not
   * create for themselves: an unexpired {@code OWNER} grant somebody else issued, or one they
   * issued to themselves while being the asset's named owner ({@code namedOwner}, which the asset
   * shell decides). There is no administrative floor here, so an {@code OWNER} grant an
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
   * Every way {@code userId} reaches the asset right now, as one {@link AccessPath} each - the
   * Herleitung of this formula (#1822, ADR-0036 Entscheidung 9): every grant that reaches the user,
   * then the organization-wide release. A group grant carries the group's attribution - its name,
   * origin and maintaining mechanism - but never a member.
   */
  public List<AccessPath> accessPaths(
      AssetType assetType, UUID assetId, UUID userId, boolean organizationWide) {
    Instant now = Instant.now();
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(userId);
    List<AssetGrant> reaching =
        cachedGrants(assetType, assetId).stream()
            .filter(grant -> !grant.isExpired(now))
            .filter(grant -> reaches(grant, userId, groupIds))
            .toList();
    Map<UUID, GroupAttribution> attributions =
        groupDirectory.attributionsById(
            reaching.stream()
                .map(AssetGrant::getSubjectGroupId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));

    List<AccessPath> paths = new ArrayList<>(reaching.size() + 1);
    for (AssetGrant grant : reaching) {
      boolean groupGrant = grant.getSubjectType() == PermissionSubjectType.GROUP;
      paths.add(
          AccessPath.ofAsset(
              groupGrant ? AccessBasis.GROUP_GRANT : AccessBasis.DIRECT_GRANT,
              grant.getRole(),
              grant.getCreatedAt(),
              groupGrant ? attributions.get(grant.getSubjectGroupId()) : null));
    }
    if (organizationWide) {
      paths.add(
          AccessPath.ofAsset(
              AccessBasis.ORGANIZATION_WIDE, organizationWideFloor(true), null, null));
    }
    return List.copyOf(paths);
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

  private Set<UUID> organizationWideAssetIds(AssetType assetType, UUID organizationId) {
    return new HashSet<>(
        grantRepository.findOrganizationWideAssetIds(assetType.value(), organizationId));
  }

  /** What an organization-wide release confers without any grant. */
  private static AssetRole organizationWideFloor(boolean organizationWide) {
    return organizationWide ? AssetRole.VIEWER : null;
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
   * {@code seed} (the organization-wide floor, or {@code null}) and every non-expired grant in
   * {@code grants} that reaches {@code userId} - directly, or via one of {@code groupIds}.
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
