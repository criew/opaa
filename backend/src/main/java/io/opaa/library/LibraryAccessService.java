package io.opaa.library;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.group.GroupMembershipResolver;
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
 * Resolves the {@link AssetRole} a user effectively holds on a {@link KnowledgeLibrary}, and the
 * set of libraries a user may read (see {@code
 * docs/features/spaces-and-assets.md#rechte-an-einem-asset-erhalten}). Management rights come
 * exclusively from an explicit {@link AssetGrant} (see {@code
 * KnowledgeLibraryService#createLibrary}, which grants the creator {@link AssetRole#OWNER} and, for
 * a group-owned library, additionally grants the owning group {@link AssetRole#MANAGER} - never
 * {@code OWNER} to the group, which would be an unbounded, non-downgradable grant) or from
 * organization-wide visibility.
 *
 * <p>Two access paths, deliberately not unified:
 *
 * <ul>
 *   <li>{@link #effectiveRole}, backing {@code canRead}/{@code canManage} for the library CRUD
 *       endpoints, is a single-library lookup on the hot path of every such request and is cached
 *       per library id, invalidated after commit whenever a grant on that library changes -
 *       mirroring {@link GroupMembershipResolver}'s cache and invalidation pattern.
 *   <li>{@link #readableLibraryIds}, backing the permission-aware vector search filter, is
 *       deliberately <b>not</b> cached: it is a single indexed query per call, so a revoked grant
 *       takes effect on the very next query without depending on a second cache-invalidation path
 *       being correct - the search filter is where a stale cache would leak data, not merely delay
 *       a UI refresh.
 * </ul>
 */
@Component
public class LibraryAccessService {

  private final AssetGrantRepository grantRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final GroupMembershipResolver membershipResolver;
  private final Cache<UUID, List<AssetGrant>> grantsByLibrary;

  public LibraryAccessService(
      AssetGrantRepository grantRepository,
      KnowledgeLibraryRepository libraryRepository,
      GroupMembershipResolver membershipResolver) {
    this.grantRepository = grantRepository;
    this.libraryRepository = libraryRepository;
    this.membershipResolver = membershipResolver;
    // Same reasoning as GroupMembershipResolver#groupIdsByUser: a stale entry only ever grants
    // access a moment too long between a completed transaction's invalidation and the next read,
    // never too little - invalidateLibrary below, called post-commit, is the primary correctness
    // mechanism. Time-based expiry is a safety net only.
    this.grantsByLibrary =
        Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(Duration.ofMinutes(10)).build();
  }

  /**
   * Whether the user may see a library's configuration (name, description, owner, document list) -
   * requires at least {@link AssetRole#VIEWER}. {@link #readableLibraryIds} is the search-facing
   * counterpart - see its own Javadoc for why the two are not unified into one call.
   */
  public boolean canRead(KnowledgeLibrary library, UUID userId, boolean systemAdmin) {
    return atLeast(effectiveRole(library, userId, systemAdmin), AssetRole.VIEWER);
  }

  /**
   * Whether the user may rename, change visibility/listed, or manage grants - requires {@link
   * AssetRole#MANAGER}. Deliberately <b>not</b> sufficient for deleting the library or (once it
   * exists) transferring ownership, which requires {@link AssetRole#OWNER} (see its Javadoc,
   * "additionally delete the asset and transfer ownership") - a {@code MANAGER} grant, including a
   * group's, must never be able to delete a library and take its {@code OWNER} grant down with it.
   */
  public boolean canManage(KnowledgeLibrary library, UUID userId, boolean systemAdmin) {
    return atLeast(effectiveRole(library, userId, systemAdmin), AssetRole.MANAGER);
  }

  /**
   * Requires at least {@code required} on {@code library}, distinguishing "no access at all" from
   * "some access, but not enough" (#436) - the single helper every library-scoped endpoint calls
   * instead of the {@code canXxx}/throw-403 pairs above, so a user who holds no {@link AssetGrant}
   * on the library at all and no organization-wide floor gets the same {@code 404} the library's
   * own lookup already produces for "does not exist", rather than a {@code 403} that confirms the
   * library is there.
   *
   * @return the caller's resolved role, at least {@code required} - callers that also need the
   *     concrete role (e.g. to embed it in a response) do not have to call {@link #effectiveRole} a
   *     second time.
   */
  public AssetRole requireRole(
      KnowledgeLibrary library, UUID userId, boolean systemAdmin, AssetRole required) {
    AssetRole role = effectiveRole(library, userId, systemAdmin);
    if (role == null) {
      throw new NotFoundException("Bibliothek nicht gefunden");
    }
    if (!role.atLeast(required)) {
      throw new AccessDeniedException("Kein Zugriff auf diese Bibliothek");
    }
    return role;
  }

  /**
   * The highest {@link AssetRole} the user holds on the library, or {@code null} if none.
   * Organization-wide visibility grants {@link AssetRole#VIEWER} to every user of the same
   * organization. Every library reaches this method through the same, single path the specification
   * in docs/features/spaces-and-assets.md#rechte-an-einem-asset-erhalten describes, with no
   * exception.
   */
  public AssetRole effectiveRole(KnowledgeLibrary library, UUID userId, boolean systemAdmin) {
    if (systemAdmin) {
      return AssetRole.OWNER;
    }

    AssetRole organizationWideFloor =
        library.getVisibility() == LibraryVisibility.ORGANIZATION ? AssetRole.VIEWER : null;
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(userId);
    return bestRole(
        grantsByLibrary.get(library.getId(), grantRepository::findByLibraryId),
        userId,
        groupIds,
        Instant.now(),
        organizationWideFloor);
  }

  /**
   * Every library id readable by the user in {@code organizationId}: direct grants, group grants
   * for the groups the user currently belongs to, and every organization-wide library - exactly the
   * formula in docs/features/spaces-and-assets.md#rechte-an-einem-asset-erhalten. Space
   * associations deliberately do not appear anywhere in this computation, per the same
   * specification section. No system-admin bypass: the vector search always reads with the calling
   * user's own rights, with no second rights context (ADR-0008 §5) - unlike {@link #effectiveRole},
   * which fail-opens system admins for library administration. That asymmetry is intentional and
   * points the safe way: an admin may administer every library but retrieves only from those the
   * formula grants them, so nothing an admin reads in a chat can come from a library they were not
   * granted.
   */
  public Set<UUID> readableLibraryIds(UUID userId, UUID organizationId) {
    Instant now = Instant.now();
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(userId);

    Set<UUID> readable = new HashSet<>();
    readable.addAll(
        grantRepository.findReadableLibraryIdsByDirectGrant(userId, organizationId, now));
    if (!groupIds.isEmpty()) {
      readable.addAll(
          grantRepository.findReadableLibraryIdsByGroupGrant(groupIds, organizationId, now));
    }
    libraryRepository
        .findByOrganizationIdAndVisibility(organizationId, LibraryVisibility.ORGANIZATION)
        .forEach(library -> readable.add(library.getId()));
    return readable;
  }

  /**
   * Every library id a <b>permission profile</b> may read: the group's own grants plus every
   * organization-wide library - the group-shaped counterpart of {@link #readableLibraryIds}, and
   * the search scope the administration's diagnosis runs a Rechteprofil in (#1053,
   * docs/features/hybrid-retrieval.md, "Das Diagnosewerkzeug").
   *
   * <p>Deliberately without the direct user grants that formula also considers: a profile is a
   * role, not a person. Uncached for the same reason {@link #readableLibraryIds} is - this is a
   * search-scope decision, where a stale cache would leak rather than merely delay.
   */
  public Set<UUID> readableLibraryIdsForGroup(UUID groupId, UUID organizationId) {
    Set<UUID> readable =
        new HashSet<>(
            grantRepository.findReadableLibraryIdsByGroupGrant(
                Set.of(groupId), organizationId, Instant.now()));
    libraryRepository
        .findByOrganizationIdAndVisibility(organizationId, LibraryVisibility.ORGANIZATION)
        .forEach(library -> readable.add(library.getId()));
    return readable;
  }

  /**
   * How many libraries each of {@code groupIds} may read, by the same formula {@link
   * #readableLibraryIdsForGroup} applies - in two queries for the whole set rather than two per
   * group. Every requested id gets an entry, including a group with no grant at all, which still
   * reaches every organization-wide library.
   */
  public Map<UUID, Integer> readableLibraryCountsForGroups(
      Collection<UUID> groupIds, UUID organizationId) {
    Instant now = Instant.now();
    Set<UUID> organizationWide =
        libraryRepository
            .findByOrganizationIdAndVisibility(organizationId, LibraryVisibility.ORGANIZATION)
            .stream()
            .map(KnowledgeLibrary::getId)
            .collect(Collectors.toSet());
    Map<UUID, Set<UUID>> grantedByGroup = new HashMap<>();
    for (AssetGrant grant : grantRepository.findActiveGroupGrants(organizationId, now)) {
      grantedByGroup
          .computeIfAbsent(grant.getSubjectGroupId(), id -> new HashSet<>())
          .add(grant.getLibraryId());
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
   * The effective {@link AssetRole} for every one of {@code libraries}, for {@code userId} - the
   * {@code listLibraries} counterpart of {@link #effectiveRole}, deliberately not built by calling
   * that method once per library:
   *
   * <ul>
   *   <li><b>Correctness:</b> {@code listLibraries} membership comes from {@link
   *       #readableLibraryIds}, which is deliberately uncached so a just-granted or just-revoked
   *       right is reflected immediately, while {@link #effectiveRole} reads the separately cached
   *       {@link #grantsByLibrary}, invalidated only after commit. This method instead reads every
   *       grant for {@code libraries} in one query, giving the same freshness guarantee, and floors
   *       the result at {@link AssetRole#VIEWER}: every library in {@code libraries} is assumed to
   *       already be in the caller's {@link #readableLibraryIds}, which the formula guarantees is
   *       reachable only at {@code VIEWER} or above - a {@code null} role would break the OpenAPI
   *       specification, which declares {@code myRole} required.
   *   <li><b>Performance:</b> one query for N libraries instead of up to N queries on a cold cache.
   * </ul>
   *
   * <p><b>Never bypasses to {@link AssetRole#OWNER} for a system admin</b> - unlike {@link
   * #effectiveRole}. {@code listLibraries} membership itself never bypasses (see {@link
   * #readableLibraryIds}'s Javadoc), so a bypassed role here would mislabel an
   * administratively-reached library as one the admin actually owns or manages. See {@code
   * myRole}'s description in the OpenAPI specification for the caller-facing consequence: a system
   * admin distinguishes "I own/manage this" from "I can see this because I administer everything"
   * via their own known admin status, not via this field.
   */
  public Map<UUID, AssetRole> effectiveRolesForReadableLibraries(
      List<KnowledgeLibrary> libraries, UUID userId) {
    Instant now = Instant.now();
    Set<UUID> libraryIds =
        libraries.stream().map(KnowledgeLibrary::getId).collect(Collectors.toSet());
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(userId);
    Map<UUID, List<AssetGrant>> grantsByLibraryId =
        grantRepository.findByLibraryIdIn(libraryIds).stream()
            .collect(Collectors.groupingBy(AssetGrant::getLibraryId));

    Map<UUID, AssetRole> roles = new HashMap<>();
    for (KnowledgeLibrary library : libraries) {
      AssetRole organizationWideFloor =
          library.getVisibility() == LibraryVisibility.ORGANIZATION ? AssetRole.VIEWER : null;
      AssetRole best =
          bestRole(
              grantsByLibraryId.getOrDefault(library.getId(), List.of()),
              userId,
              groupIds,
              now,
              organizationWideFloor);
      // Every library here is assumed to already be in the caller's readableLibraryIds, which the
      // formula guarantees is reachable only at VIEWER or above - see this method's own Javadoc.
      roles.put(library.getId(), best != null ? best : AssetRole.VIEWER);
    }
    return roles;
  }

  /**
   * The single rights-resolution formula both {@link #effectiveRole} and {@link
   * #effectiveRolesForReadableLibraries} apply, over two different grant sources (a single cached
   * library's grants vs. a batch-loaded map across many), so the formula itself cannot drift
   * between the two call sites. Highest role among {@code seed} (the caller's starting floor, e.g.
   * organization-wide visibility, or {@code null} for none) and every non-expired grant in {@code
   * grants} that reaches {@code userId} - directly, or via one of {@code groupIds}.
   */
  private static AssetRole bestRole(
      List<AssetGrant> grants, UUID userId, Set<UUID> groupIds, Instant now, AssetRole seed) {
    AssetRole best = seed;
    for (AssetGrant grant : grants) {
      if (grant.isExpired(now)) {
        continue;
      }
      boolean reaches =
          (grant.getSubjectType() == PermissionSubjectType.USER
                  && grant.getSubjectUserId().equals(userId))
              || (grant.getSubjectType() == PermissionSubjectType.GROUP
                  && groupIds.contains(grant.getSubjectGroupId()));
      if (reaches && (best == null || grant.getRole().atLeast(best))) {
        best = grant.getRole();
      }
    }
    return best;
  }

  /**
   * Evicts the cached grant list for a library, called after commit (or rollback) whenever one of
   * its grants changes - see {@code AssetGrantService#invalidateAfterCommit} for why "after commit"
   * rather than inline.
   */
  public void invalidateLibrary(UUID libraryId) {
    grantsByLibrary.invalidate(libraryId);
  }

  private static boolean atLeast(AssetRole role, AssetRole required) {
    return role != null && role.atLeast(required);
  }
}
