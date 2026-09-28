package io.opaa.space.web;

import io.opaa.api.dto.AssetSpaceAssociationListResponse;
import io.opaa.asset.AssetTypes;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.permission.AssetType;
import io.opaa.space.SpaceAssetAssociationService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The spaces an asset of any type is associated with, a subresource of {@code AssetController}. An
 * asset is named by type plus id; a malformed or unknown type answers {@code 404} like an unknown
 * asset.
 */
@RestController
@RequestMapping("/api/v1/assets/{assetType}/{assetId}")
public class AssetSpaceController {

  private final SpaceAssetAssociationService associationService;
  private final AssetTypes assetTypes;

  public AssetSpaceController(
      SpaceAssetAssociationService associationService, AssetTypes assetTypes) {
    this.associationService = associationService;
    this.assetTypes = assetTypes;
  }

  @GetMapping("/spaces")
  public AssetSpaceAssociationListResponse listAssetSpaceAssociations(
      @PathVariable String assetType, @PathVariable UUID assetId, @Caller CurrentUser caller) {
    return SpaceAssetAssociationResponseMapper.toAssetSpaceListResponse(
        associationService.listForAsset(typeOf(assetType), assetId, caller));
  }

  private AssetType typeOf(String assetType) {
    return assetTypes.require(assetType).assetType();
  }
}
