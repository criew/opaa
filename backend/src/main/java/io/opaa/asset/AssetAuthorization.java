package io.opaa.asset;

import io.opaa.api.types.AccessBasis;
import io.opaa.api.types.AssetRole;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.permission.AccessPath;
import io.opaa.permission.AssetAccessService;
import io.opaa.permission.AssetType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The role a person holds on one asset for <b>administering</b> it: the rights formula of {@link
 * AssetAccessService} plus the one floor administration adds - a system administrator counts as
 * {@link AssetRole#OWNER} (docs/features/spaces-and-assets.md#rechte-an-einem-asset-erhalten). The
 * floor never reaches the search: {@link AssetAccessService#readableAssetIds} knows no such branch,
 * so administering an asset is never reading it.
 */
@Component
public class AssetAuthorization {

  private final AssetRepository assetRepository;
  private final AssetAccessService accessService;
  private final AssetTypes assetTypes;

  public AssetAuthorization(
      AssetRepository assetRepository, AssetAccessService accessService, AssetTypes assetTypes) {
    this.assetRepository = assetRepository;
    this.accessService = accessService;
    this.assetTypes = assetTypes;
  }

  /**
   * The asset of {@code assetType} with {@code assetId} in {@code organizationId}. An unknown id,
   * another type and another organization all answer the same {@code 404}, so the existence of a
   * foreign asset is never confirmed.
   */
  public Asset load(AssetType assetType, UUID assetId, UUID organizationId) {
    AssetTypeDefinition definition = assetTypes.require(assetType);
    return assetRepository
        .findById(assetId)
        .filter(asset -> asset.getAssetType().equals(assetType))
        .filter(asset -> asset.getOrganizationId().equals(organizationId))
        .orElseThrow(() -> new NotFoundException(AssetTypes.notFoundMessage(definition)));
  }

  /** The highest role {@code userId} holds on the asset for administration, or {@code null}. */
  public AssetRole effectiveRole(Asset asset, UUID userId, boolean systemAdmin) {
    if (systemAdmin) {
      return AssetRole.OWNER;
    }
    return accessService.effectiveRole(
        asset.getAssetType(), asset.getId(), userId, asset.isOrganizationWide());
  }

  /**
   * Requires at least {@code required}, telling "no access at all" from "some access, but not
   * enough" (#436): a person the asset does not reach gets the same {@code 404} as for an unknown
   * asset, one who reaches it too weakly a {@code 403}.
   *
   * @return the caller's resolved role, at least {@code required}.
   */
  public AssetRole requireRole(Asset asset, UUID userId, boolean systemAdmin, AssetRole required) {
    AssetTypeDefinition definition = assetTypes.require(asset.getAssetType());
    AssetRole role = effectiveRole(asset, userId, systemAdmin);
    if (role == null) {
      throw new NotFoundException(AssetTypes.notFoundMessage(definition));
    }
    if (!role.atLeast(required)) {
      throw new AccessDeniedException("Kein Zugriff auf diese " + definition.singular());
    }
    return role;
  }

  /**
   * Requires at least {@code required} on the asset's <b>content</b>, which the formula alone
   * grants: a person the asset does not reach at all gets the {@code 404} of {@link #requireRole},
   * one it reaches only through the administration or too weakly a {@code 403}. Reads the grants
   * uncached, like {@link AssetAccessService#readableAssetIds}, so a revoked right stops on the
   * next request.
   *
   * @return the caller's role by the formula, at least {@code required}.
   */
  public AssetRole requireContentRole(
      Asset asset, UUID userId, boolean systemAdmin, AssetRole required) {
    AssetTypeDefinition definition = assetTypes.require(asset.getAssetType());
    requireRole(asset, userId, systemAdmin, AssetRole.VIEWER);
    AssetRole role =
        accessService
            .effectiveRoles(
                asset.getAssetType(),
                Set.of(asset.getId()),
                userId,
                asset.isOrganizationWide() ? Set.of(asset.getId()) : Set.of())
            .get(asset.getId());
    if (role == null) {
      throw new AccessDeniedException(
          "Für diese "
              + definition.singular()
              + " liegt keine Leseberechtigung vor; Verwaltungsrechte genügen dafür nicht.");
    }
    if (!role.atLeast(required)) {
      throw new AccessDeniedException("Kein Zugriff auf diese " + definition.singular());
    }
    return role;
  }

  public boolean canRead(Asset asset, UUID userId, boolean systemAdmin) {
    AssetRole role = effectiveRole(asset, userId, systemAdmin);
    return role != null && role.atLeast(AssetRole.VIEWER);
  }

  /**
   * Whether the person may manage the asset - {@link AssetRole#MANAGER}; deleting it and handing it
   * on need {@link AssetRole#OWNER}.
   */
  public boolean canManage(Asset asset, UUID userId, boolean systemAdmin) {
    AssetRole role = effectiveRole(asset, userId, systemAdmin);
    return role != null && role.atLeast(AssetRole.MANAGER);
  }

  /**
   * Why {@code userId} reaches the asset - the ways of the formula, then the system administration
   * where it carries the role (#1822, ADR-0036 Entscheidung 9). A capability never appears: it
   * opens an Anlegepfad, never a content.
   */
  public List<AccessPath> accessPaths(Asset asset, UUID userId, boolean systemAdmin) {
    List<AccessPath> paths =
        new ArrayList<>(
            accessService.accessPaths(
                asset.getAssetType(), asset.getId(), userId, asset.isOrganizationWide()));
    if (systemAdmin) {
      paths.add(AccessPath.ofAsset(AccessBasis.SYSTEM_ADMINISTRATION, AssetRole.OWNER, null, null));
    }
    return List.copyOf(paths);
  }
}
