package io.opaa.library;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.permission.AssetAccessService;
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
 * docs/features/spaces-and-assets.md#rechte-an-einem-asset-erhalten}). The grant half of that
 * formula is {@link AssetAccessService}, shared with every other asset type; this class adds the
 * two things that hold for a library alone - the {@link AssetRole#VIEWER} floor an
 * organization-wide library grants, and the {@link AssetRole#OWNER} floor library administration
 * grants a system admin - and keeps the library-shaped call surface every library endpoint uses.
 *
 * <p>Management rights come exclusively from an explicit grant (see {@code
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
 *       (in {@link AssetAccessService}) per asset, invalidated after commit whenever a grant on
 *       that library changes.
 *   <li>{@link #readableLibraryIds}, backing the permission-aware vector search filter, is
 *       deliberately <b>not</b> cached: it is a single indexed query per call, so a revoked grant
 *       takes effect on the very next query without depending on a second cache-invalidation path
 *       being correct - the search filter is where a stale cache would leak data, not merely delay
 *       a UI refresh.
 * </ul>
 */
@Component
public class LibraryAccessService {

  private final AssetAccessService assetAccessService;
  private final KnowledgeLibraryRepository libraryRepository;

  public LibraryAccessService(
      AssetAccessService assetAccessService, KnowledgeLibraryRepository libraryRepository) {
    this.assetAccessService = assetAccessService;
    this.libraryRepository = libraryRepository;
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
   * instead of the {@code canXxx}/throw-403 pairs above, so a user who holds no grant on the
   * library at all and no organization-wide floor gets the same {@code 404} the library's own
   * lookup already produces for "does not exist", rather than a {@code 403} that confirms the
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
    return assetAccessService.effectiveRole(
        KnowledgeLibrary.ASSET_TYPE, library.getId(), userId, organizationWideFloor(library));
  }

  /**
   * Every library id readable by the user in {@code organizationId}: direct grants, group grants
   * for the groups the user currently belongs to, and every organization-wide library - exactly the
   * formula in docs/features/spaces-and-assets.md#rechte-an-einem-asset-erhalten. Space
   * associations deliberately do not appear anywhere in this computation, per the same
   * specification section. No system-admin bypass: the vector search always reads with the calling
   * user's own rights, with no second rights context
   * (docs/features/spaces-and-assets.md#ein-agent-liest-immer-mit-den-rechten-des-nutzers) - unlike
   * {@link #effectiveRole}, which fail-opens system admins for library administration. That
   * asymmetry is intentional and points the safe way: an admin may administer every library but
   * retrieves only from those the formula grants them, so nothing an admin reads in a chat can come
   * from a library they were not granted.
   */
  public Set<UUID> readableLibraryIds(UUID userId, UUID organizationId) {
    Set<UUID> readable =
        assetAccessService.readableAssetIds(KnowledgeLibrary.ASSET_TYPE, userId, organizationId);
    readable.addAll(organizationWideLibraryIds(organizationId));
    return readable;
  }

  /**
   * Whether {@code userId} holds {@link AssetRole#OWNER} on {@code library} on a basis they did not
   * create for themselves - see {@link AssetAccessService#holdsIndependentOwnerRole} for the rule
   * itself. Used by {@code LibraryDiagnosticsLockService} for the one rule that must hold against
   * the administration itself; every other library endpoint keeps using {@link #requireRole}.
   *
   * <p><b>The named-owner exception is only half closed, and the open half is administratively
   * reachable.</b> {@code library.getOwnerUserId()} is immutable and has no setter. {@code
   * library.getOwnerGroupId()} is immutable as well, but membership in that group is not: {@code
   * GroupController#addMember} is open to {@code SYSTEM_ADMIN}, {@code GroupService#addMember}
   * knows no self-exclusion, and its {@code rejectOrgUnit} guard refuses {@code ORG_UNIT} and
   * {@code IDENTITY_PROVIDER} groups but leaves {@code AD_HOC} groups editable. An administrator
   * can therefore add themselves to an {@code AD_HOC} owning group in one step, become {@code
   * namedOwner}, and validate their own self-issued {@code OWNER} grant. That path stays open by
   * decision, not by omission: docs/features/hybrid-retrieval.md, Berechtigungs-Leitplanken (e).
   * Closing it would be a change to group administration, not to this method.
   */
  public boolean holdsIndependentOwnerRole(KnowledgeLibrary library, UUID userId) {
    Set<UUID> groupIds = assetAccessService.groupIdsForUser(userId);
    boolean namedOwner =
        userId.equals(library.getOwnerUserId())
            || (library.getOwnerGroupId() != null && groupIds.contains(library.getOwnerGroupId()));
    return assetAccessService.holdsIndependentOwnerRole(
        KnowledgeLibrary.ASSET_TYPE, library.getId(), userId, groupIds, namedOwner);
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
        assetAccessService.grantedAssetIdsForGroup(
            KnowledgeLibrary.ASSET_TYPE, groupId, organizationId);
    readable.addAll(organizationWideLibraryIds(organizationId));
    return readable;
  }

  /**
   * How many libraries each of {@code groupIds} may read, by the same formula {@link
   * #readableLibraryIdsForGroup} applies - in two queries for the whole set rather than two per
   * group. Every requested id gets an entry, including a group with no grant at all, which still
   * reaches every organization-wide library.
   */
  public Map<UUID, Integer> readableLibraryCountsForGroups(
      java.util.Collection<UUID> groupIds, UUID organizationId) {
    Set<UUID> organizationWide = organizationWideLibraryIds(organizationId);
    Map<UUID, Set<UUID>> grantedByGroup =
        assetAccessService.grantedAssetIdsByGroup(KnowledgeLibrary.ASSET_TYPE, organizationId);

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
   * that method once per library: list membership comes from {@link #readableLibraryIds}, which is
   * uncached, while {@link #effectiveRole} reads a cache invalidated only after commit. {@link
   * AssetAccessService#effectiveRoles} instead reads every grant for {@code libraries} in one
   * query, giving the same freshness guarantee - and the result is floored at {@link
   * AssetRole#VIEWER}: every library in {@code libraries} is assumed to already be in the caller's
   * {@link #readableLibraryIds}, which the formula guarantees is reachable only at {@code VIEWER}
   * or above - a {@code null} role would break the OpenAPI specification, which declares {@code
   * myRole} required.
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
    Set<UUID> libraryIds =
        libraries.stream().map(KnowledgeLibrary::getId).collect(Collectors.toSet());
    Map<UUID, AssetRole> floors = new HashMap<>();
    for (KnowledgeLibrary library : libraries) {
      AssetRole floor = organizationWideFloor(library);
      if (floor != null) {
        floors.put(library.getId(), floor);
      }
    }

    Map<UUID, AssetRole> roles =
        assetAccessService.effectiveRoles(KnowledgeLibrary.ASSET_TYPE, libraryIds, userId, floors);
    // Every library here is assumed to already be in the caller's readableLibraryIds, which the
    // formula guarantees is reachable only at VIEWER or above - see this method's own Javadoc.
    roles.replaceAll((id, role) -> role != null ? role : AssetRole.VIEWER);
    return roles;
  }

  /**
   * Evicts the cached grant list for a library, called after commit (or rollback) whenever one of
   * its grants changes - see {@code AssetGrantService#invalidateAfterCommit} for why "after commit"
   * rather than inline.
   */
  public void invalidateLibrary(UUID libraryId) {
    assetAccessService.invalidateAsset(KnowledgeLibrary.ASSET_TYPE, libraryId);
  }

  private static AssetRole organizationWideFloor(KnowledgeLibrary library) {
    return library.getVisibility() == LibraryVisibility.ORGANIZATION ? AssetRole.VIEWER : null;
  }

  private Set<UUID> organizationWideLibraryIds(UUID organizationId) {
    return new HashSet<>(
        libraryRepository.findIdsByOrganizationIdAndVisibility(
            organizationId, LibraryVisibility.ORGANIZATION));
  }

  private static boolean atLeast(AssetRole role, AssetRole required) {
    return role != null && role.atLeast(required);
  }
}
