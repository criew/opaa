package io.opaa.asset;

import io.opaa.api.types.AssetRole;
import io.opaa.auth.CurrentUser;
import io.opaa.permission.AssetType;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Herleitung "warum sehe ich das" for the caller themselves, for an asset of any type (#1822).
 * It requires {@link AssetRole#VIEWER} like the asset's own view, so an asset the caller does not
 * reach answers {@code 404} rather than an empty derivation that would confirm its existence.
 */
@Service
@Transactional(readOnly = true)
public class AssetAccessDerivationService {

  private final AssetAuthorization authorization;

  AssetAccessDerivationService(AssetAuthorization authorization) {
    this.authorization = authorization;
  }

  public AssetAccessDerivation derive(AssetType assetType, UUID assetId, CurrentUser caller) {
    Asset asset = authorization.load(assetType, assetId, caller.organizationId());
    AssetRole role =
        authorization.requireRole(asset, caller.id(), caller.isSystemAdmin(), AssetRole.VIEWER);
    return new AssetAccessDerivation(
        asset.getAssetType(),
        asset.getId(),
        role,
        authorization.accessPaths(asset, caller.id(), caller.isSystemAdmin()));
  }
}
