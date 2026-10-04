package io.opaa.asset;

import io.opaa.common.AccessDeniedException;
import io.opaa.permission.AssetType;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The Nur-Besitzerin-Regel as the application states it: an owner-only asset is never shared,
 * handed on or released, and never read in a foreign rights context. It is no level of the share
 * cap and has no setting; the database enforces the same rule underneath ({@link Asset}).
 */
@Component
public class OwnerOnlyRule {

  /** The code of the {@code 403} every refused sharing attempt answers with. */
  public static final String OWNER_ONLY_ASSET = "OWNER_ONLY_ASSET";

  private final AssetRepository assetRepository;
  private final AssetTypes assetTypes;

  OwnerOnlyRule(AssetRepository assetRepository, AssetTypes assetTypes) {
    this.assetRepository = assetRepository;
    this.assetTypes = assetTypes;
  }

  /** Refuses with {@code 403 OWNER_ONLY_ASSET} unless the asset may reach anyone but its owner. */
  public void requireShareable(OwnedAsset asset) {
    if (asset.isOwnerOnly()) {
      String singular = assetTypes.require(asset.getAssetType()).singular();
      throw new AccessDeniedException(
          "Diese "
              + singular
              + " ist privat und gehört allein ihrer Besitzerin. Sie lässt sich weder teilen noch"
              + " übertragen noch für Fremdzugänge freigeben.",
          OWNER_ONLY_ASSET);
    }
  }

  /** {@code readableIds} without the owner-only assets of {@code assetType} - a new set. */
  public Set<UUID> withoutOwnerOnly(
      AssetType assetType, UUID organizationId, Set<UUID> readableIds) {
    Set<UUID> shared = new HashSet<>(readableIds);
    if (!shared.isEmpty()) {
      shared.removeAll(assetRepository.findOwnerOnlyIds(assetType, organizationId));
    }
    return shared;
  }
}
