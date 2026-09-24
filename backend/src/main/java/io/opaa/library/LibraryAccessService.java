package io.opaa.library;

import io.opaa.api.types.AssetRole;
import io.opaa.asset.AssetAuthorization;
import io.opaa.permission.AssetAccessService;
import io.opaa.permission.AssetReach;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The library-shaped surface of the asset rights: every library endpoint asks here, and this class
 * answers from the asset shell - {@link AssetAccessService} for the formula (grants and the
 * organization-wide release), {@link AssetAuthorization} for administration, where a system
 * administrator counts as {@link AssetRole#OWNER}. Reading a library's content ({@link
 * #requireContentRead}) needs the formula, not the administration - the shell's rule for every
 * type.
 *
 * <p>Two access paths, deliberately not unified: {@link #effectiveRole} is cached per library for
 * the CRUD hot path, {@link #readableLibraryIds} backs the search filter and is never cached - see
 * {@link AssetAccessService}.
 */
@Component
public class LibraryAccessService {

  private final AssetAccessService assetAccessService;
  private final AssetAuthorization authorization;

  public LibraryAccessService(
      AssetAccessService assetAccessService, AssetAuthorization authorization) {
    this.assetAccessService = assetAccessService;
    this.authorization = authorization;
  }

  /** Whether the user may see a library's configuration - requires {@link AssetRole#VIEWER}. */
  public boolean canRead(KnowledgeLibrary library, UUID userId, boolean systemAdmin) {
    return authorization.canRead(library, userId, systemAdmin);
  }

  /**
   * Whether the user may rename, change visibility/listed or manage grants - {@link
   * AssetRole#MANAGER}. Deleting and handing on need {@link AssetRole#OWNER}.
   */
  public boolean canManage(KnowledgeLibrary library, UUID userId, boolean systemAdmin) {
    return authorization.canManage(library, userId, systemAdmin);
  }

  /**
   * Requires at least {@code required}: {@code 404} for a person the library does not reach at all,
   * {@code 403} for one it reaches too weakly (#436) - see {@link AssetAuthorization#requireRole}.
   */
  public AssetRole requireRole(
      KnowledgeLibrary library, UUID userId, boolean systemAdmin, AssetRole required) {
    return authorization.requireRole(library, userId, systemAdmin, required);
  }

  /** The highest {@link AssetRole} the user holds on the library, or {@code null} if none. */
  public AssetRole effectiveRole(KnowledgeLibrary library, UUID userId, boolean systemAdmin) {
    return authorization.effectiveRole(library, userId, systemAdmin);
  }

  /**
   * Every library id readable by the user in {@code organizationId} - exactly the formula of
   * docs/features/spaces-and-assets.md#rechte-an-einem-asset-erhalten, with no system-admin bypass:
   * the search always reads with the calling user's own rights. An admin may administer every
   * library but retrieves only from those the formula grants them.
   */
  public Set<UUID> readableLibraryIds(UUID userId, UUID organizationId) {
    return assetAccessService.readableAssetIds(KnowledgeLibrary.ASSET_TYPE, userId, organizationId);
  }

  /**
   * Requires the permission a library's <b>content</b> needs (#1828), the shell's rule for every
   * asset type ({@link AssetAuthorization#requireContentRole}): a person the library does not reach
   * keeps its {@code 404}, a system admin without a grant gets {@code 403} - administering a
   * library is not reading it.
   */
  public void requireContentRead(KnowledgeLibrary library, UUID userId, boolean systemAdmin) {
    authorization.requireContentRole(library, userId, systemAdmin, AssetRole.VIEWER);
  }

  /**
   * Whether {@code userId} holds {@link AssetRole#OWNER} on {@code library} on a basis they did not
   * create for themselves - see {@link AssetAccessService#holdsIndependentOwnerRole}. Used by
   * {@code LibraryDiagnosticsLockService} for the one rule that must hold against the
   * administration itself.
   *
   * <p><b>The named-owner exception is only half closed, and the open half is administratively
   * reachable:</b> membership in an owning {@code AD_HOC} group is editable by a system
   * administrator. That path stays open by decision (docs/features/hybrid-retrieval.md,
   * Berechtigungs-Leitplanken (e)); closing it would be a change to group administration.
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
   * Every library id a <b>permission profile</b> may read - the search scope the administration's
   * diagnosis runs a Rechteprofil in (#1053, docs/features/hybrid-retrieval.md).
   */
  public Set<UUID> readableLibraryIdsForGroup(UUID groupId, UUID organizationId) {
    return assetAccessService.readableAssetIdsForGroup(
        KnowledgeLibrary.ASSET_TYPE, groupId, organizationId);
  }

  /** How many libraries each of {@code groupIds} may read, by the formula of the profile. */
  public Map<UUID, Integer> readableLibraryCountsForGroups(
      Collection<UUID> groupIds, UUID organizationId) {
    return assetAccessService.readableAssetCountsForGroups(
        KnowledgeLibrary.ASSET_TYPE, groupIds, organizationId);
  }

  /**
   * The effective {@link AssetRole} for every one of {@code libraries}, for {@code userId} - the
   * list counterpart of {@link #effectiveRole}, reading every grant in one query (see {@link
   * AssetAccessService#effectiveRoles}). Floored at {@link AssetRole#VIEWER}: every library passed
   * here is already in the caller's {@link #readableLibraryIds}, and {@code myRole} is required by
   * the specification.
   *
   * <p><b>Never bypasses to {@link AssetRole#OWNER} for a system admin</b>: the list itself never
   * does, so a bypassed role would mislabel an administratively reached library as one the admin
   * owns.
   */
  public Map<UUID, AssetRole> effectiveRolesForReadableLibraries(
      List<KnowledgeLibrary> libraries, UUID userId) {
    Set<UUID> libraryIds =
        libraries.stream().map(KnowledgeLibrary::getId).collect(Collectors.toSet());
    Map<UUID, AssetRole> roles =
        assetAccessService.effectiveRoles(KnowledgeLibrary.ASSET_TYPE, libraryIds, userId);
    roles.replaceAll((id, role) -> role != null ? role : AssetRole.VIEWER);
    return roles;
  }

  /**
   * How far each of {@code libraries} reaches right now - the figures the overview turns into its
   * reach badge (#1931). One grouped read for the whole page.
   */
  public Map<UUID, AssetReach> reachOf(List<KnowledgeLibrary> libraries) {
    return assetAccessService.reachByAsset(
        KnowledgeLibrary.ASSET_TYPE,
        libraries.stream().map(KnowledgeLibrary::getId).collect(Collectors.toSet()));
  }

  /** Evicts the cached grant list for a library, after its grants changed. */
  public void invalidateLibrary(UUID libraryId) {
    assetAccessService.invalidateAsset(KnowledgeLibrary.ASSET_TYPE, libraryId);
  }
}
