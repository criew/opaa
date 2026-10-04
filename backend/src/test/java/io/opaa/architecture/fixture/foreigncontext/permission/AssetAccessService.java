package io.opaa.architecture.fixture.foreigncontext.permission;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AssetAccessService {

  public Set<UUID> readableAssetIds(String assetType, UUID userId, UUID organizationId) {
    return Set.of();
  }

  public Object readableAssets(String assetType, UUID userId, UUID organizationId) {
    return null;
  }

  public Map<UUID, Object> effectiveRoles(String assetType, Set<UUID> ids, UUID userId) {
    return Map.of();
  }
}
