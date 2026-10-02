package io.opaa.asset;

import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.permission.AssetAccessService;
import io.opaa.permission.AssetType;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The caller's own favorites (ADR-0039, Entscheidung 7). Marking and unmarking need read access by
 * the rights formula - the administration floor does not count - and answer {@code 404} otherwise,
 * so the answer never confirms an asset the caller cannot read. Deliberately silent: no event, no
 * protocol entry, no history.
 */
@Service
public class AssetFavoriteService {

  private final AssetAuthorization authorization;
  private final AssetAccessService accessService;
  private final AssetTypes assetTypes;
  private final AssetFavoriteRepository favorites;

  public AssetFavoriteService(
      AssetAuthorization authorization,
      AssetAccessService accessService,
      AssetTypes assetTypes,
      AssetFavoriteRepository favorites) {
    this.authorization = authorization;
    this.accessService = accessService;
    this.assetTypes = assetTypes;
    this.favorites = favorites;
  }

  @Transactional
  public void mark(AssetType assetType, UUID assetId, CurrentUser caller) {
    Asset asset = requireReadable(assetType, assetId, caller);
    favorites.mark(asset.getId(), caller.id(), asset.getOrganizationId(), Instant.now());
  }

  @Transactional
  public void unmark(AssetType assetType, UUID assetId, CurrentUser caller) {
    Asset asset = requireReadable(assetType, assetId, caller);
    favorites.unmark(asset.getId(), caller.id());
  }

  private Asset requireReadable(AssetType assetType, UUID assetId, CurrentUser caller) {
    Asset asset = authorization.load(assetType, assetId, caller.organizationId());
    boolean readable =
        accessService.effectiveRoles(assetType, Set.of(assetId), caller.id()).get(assetId) != null;
    if (!readable) {
      throw new NotFoundException(AssetTypes.notFoundMessage(assetTypes.require(assetType)));
    }
    return asset;
  }
}
