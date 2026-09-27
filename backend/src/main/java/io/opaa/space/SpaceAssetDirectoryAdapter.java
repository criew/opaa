package io.opaa.space;

import io.opaa.auth.CurrentUser;
import io.opaa.permission.AssetType;
import io.opaa.permission.SpaceAssetDirectory;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Answers {@link SpaceAssetDirectory} through {@link SpaceAssetAssociationService}. */
@Component
class SpaceAssetDirectoryAdapter implements SpaceAssetDirectory {

  private final SpaceAssetAssociationService associationService;

  SpaceAssetDirectoryAdapter(SpaceAssetAssociationService associationService) {
    this.associationService = associationService;
  }

  @Override
  public Set<UUID> assetIdsInSpace(UUID spaceId, AssetType assetType, CurrentUser caller) {
    return associationService.assetIdsInSpace(spaceId, assetType, caller);
  }
}
