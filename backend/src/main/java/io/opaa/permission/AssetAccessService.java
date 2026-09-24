package io.opaa.permission;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.api.types.AccessBasis;
import io.opaa.api.types.AssetGrantSubjectType;
import io.opaa.api.types.AssetRole;
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
 * of their groups, or a grant to "Alle Konten" (see
 * docs/features/spaces-and-assets.md#rechte-an-einem-asset-erhalten). All three read the same
 * table: organization-wide reach is a grant like any other since #1931 (ADR-0037), so no caller
 * hands in a reach it read off the asset shell.
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

  /** The highest {@link AssetRole} {@code userId} holds on the asset, or {@code null} if none. */
  public AssetRole effectiveRole(AssetType assetType, UUID assetId, UUID userId) {
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(userId);
    return bestRole(cachedGrants(assetType, assetId), userId, groupIds, Instant.now());
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
   * @return one entry per requested id; {@code null} value where nothing reaches the user.
   */
  public Map<UUID, AssetRole> effectiveRoles(AssetType assetType, Set<UUID> assetIds, UUID userId) {
    Instant now = Instant.now();
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(userId);
    Map<UUID, List<AssetGrant>> grantsByAssetId =
        grantRepository.findByAssetTypeAndAssetIdIn(assetType, assetIds).stream()
            .collect(Collectors.groupingBy(AssetGrant::getAssetId));

    Map<UUID, AssetRole> roles = new HashMap<>();
    for (UUID assetId : assetIds) {
      roles.put(
          assetId,
          bestRole(grantsByAssetId.getOrDefault(assetId, List.of()), userId, groupIds, now));
    }
    return roles;
  }

  /**
   * Every asset id of {@code assetType} in {@code organizationId} that {@code userId} may read:
   * direct grants, grants to the groups the user currently belongs to, and grants to "Alle
   * Beschaeftigten". Space associations appear nowhere in this formula.
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
    readable.addAll(allAccountsAssetIds(assetType, organizationId, now));
    return readable;
  }

  /**
   * Every asset id of {@code assetType} a <b>permission profile</b> may read: the group's own
   * non-expired grants plus every asset granted to "Alle Konten" - the group-shaped counterpart of
   * {@link #readableAssetIds}, deliberately without the direct user grants: a profile is a group,
   * not a person (#1053, ADR-0036 Entscheidung 7). Uncached for the same reason.
   */
  public Set<UUID> readableAssetIdsForGroup(
      AssetType assetType, UUID groupId, UUID organizationId) {
    Instant now = Instant.now();
    Set<UUID> readable =
        new HashSet<>(
            grantRepository.findReadableAssetIdsByGroupGrant(
                assetType, Set.of(groupId), organizationId, now));
    readable.addAll(allAccountsAssetIds(assetType, organizationId, now));
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
    Instant now = Instant.now();
    Set<UUID> allAccounts = allAccountsAssetIds(assetType, organizationId, now);
    Map<UUID, Set<UUID>> grantedByGroup = new HashMap<>();
    for (AssetGrant grant : grantRepository.findActiveGroupGrants(assetType, organizationId, now)) {
      grantedByGroup
          .computeIfAbsent(grant.getSubjectGroupId(), id -> new HashSet<>())
          .add(grant.getAssetId());
    }
    Map<UUID, Integer> counts = new HashMap<>();
    for (UUID groupId : groupIds) {
      Set<UUID> readable = new HashSet<>(allAccounts);
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
   * Herleitung of this formula (#1822, ADR-0036 Entscheidung 9). A group grant carries the group's
   * attribution - its name, origin and maintaining mechanism - but never a member; a grant to "Alle
   * Beschaeftigten" keeps the basis {@code ORGANIZATION_WIDE} the Herleitung has always used for
   * it.
   */
  public List<AccessPath> accessPaths(AssetType assetType, UUID assetId, UUID userId) {
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

    List<AccessPath> paths = new ArrayList<>(reaching.size());
    for (AssetGrant grant : reaching) {
      paths.add(
          AccessPath.ofAsset(
              basisOf(grant.getSubjectType()),
              grant.getRole(),
              grant.getCreatedAt(),
              grant.getSubjectType() == AssetGrantSubjectType.GROUP
                  ? attributions.get(grant.getSubjectGroupId())
                  : null));
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

  /**
   * How far each of {@code assetIds} reaches right now (#1931) - the figures an overview turns into
   * its reach badge. Every requested id gets an entry, including one no grant reaches.
   */
  public Map<UUID, AssetReach> reachByAsset(AssetType assetType, Set<UUID> assetIds) {
    Map<UUID, AssetReach> reach = new HashMap<>();
    for (UUID assetId : assetIds) {
      reach.put(assetId, AssetReach.NONE);
    }
    if (assetIds.isEmpty()) {
      return reach;
    }
    for (Object[] row :
        grantRepository.countActiveGrantsBySubjectType(assetType, assetIds, Instant.now())) {
      UUID assetId = (UUID) row[0];
      AssetGrantSubjectType subjectType = (AssetGrantSubjectType) row[1];
      int count = ((Number) row[2]).intValue();
      reach.computeIfPresent(assetId, (id, current) -> current.plus(subjectType, count));
    }
    return reach;
  }

  /** The Herleitungsgrund a grant of this subject kind carries. */
  private static AccessBasis basisOf(AssetGrantSubjectType subjectType) {
    return switch (subjectType) {
      case USER -> AccessBasis.DIRECT_GRANT;
      case GROUP -> AccessBasis.GROUP_GRANT;
      case ALL_ACCOUNTS -> AccessBasis.ORGANIZATION_WIDE;
    };
  }

  private Set<UUID> allAccountsAssetIds(AssetType assetType, UUID organizationId, Instant now) {
    return new HashSet<>(
        grantRepository.findAssetIdsGrantedToAllAccounts(assetType, organizationId, now));
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
   * every non-expired grant in {@code grants} that reaches {@code userId} - directly, via one of
   * {@code groupIds}, or as one of all accounts.
   */
  private static AssetRole bestRole(
      Collection<AssetGrant> grants, UUID userId, Set<UUID> groupIds, Instant now) {
    AssetRole best = null;
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

  /**
   * Whether {@code grant} reaches {@code userId} - directly, via one of {@code groupIds}, or
   * because it reaches every account. The {@code ALL_ACCOUNTS} branch is the one special case the
   * formula keeps, and it stands here alone (ADR-0037, Entscheidung 1); the organization boundary
   * behind it is the one every caller already applies by reading the grants of an asset of that
   * organization.
   */
  private static boolean reaches(AssetGrant grant, UUID userId, Set<UUID> groupIds) {
    return switch (grant.getSubjectType()) {
      case USER -> grant.getSubjectUserId().equals(userId);
      case GROUP -> groupIds.contains(grant.getSubjectGroupId());
      case ALL_ACCOUNTS -> true;
    };
  }
}
