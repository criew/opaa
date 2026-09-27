package io.opaa.asset.web;

import io.opaa.api.dto.AssetType;
import io.opaa.api.dto.CatalogEntryResponse;
import io.opaa.api.dto.CatalogPageResponse;
import io.opaa.asset.AssetCatalogEntry;
import io.opaa.asset.AssetCatalogPage;
import io.opaa.asset.AssetCatalogRow;
import io.opaa.permission.web.SuccessionStateResponseMapper;

/** Maps the catalog of {@code io.opaa.asset} onto its generated counterparts. */
final class CatalogResponseMapper {

  private CatalogResponseMapper() {}

  static CatalogPageResponse toResponse(AssetCatalogPage page) {
    return new CatalogPageResponse(
        page.entries().stream().map(CatalogResponseMapper::toResponse).toList(),
        page.page(),
        page.size(),
        page.totalElements(),
        page.totalPages());
  }

  static CatalogEntryResponse toResponse(AssetCatalogEntry entry) {
    AssetCatalogRow asset = entry.asset();
    return new CatalogEntryResponse(
            AssetType.fromValue(asset.getAssetType().value()),
            asset.getId(),
            asset.getName(),
            asset.getOwnerType(),
            asset.getOrigin(),
            entry.accessible(),
            asset.isListed(),
            entry.itemCount(),
            entry.spaceCount())
        .description(asset.getDescription())
        .ownerLabel(entry.ownerLabel())
        .succession(SuccessionStateResponseMapper.toStateResponse(entry.succession()));
  }
}
