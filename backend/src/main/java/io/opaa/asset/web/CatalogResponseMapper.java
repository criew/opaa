package io.opaa.asset.web;

import io.opaa.api.dto.AssetType;
import io.opaa.api.dto.CatalogEntryResponse;
import io.opaa.api.dto.CatalogPageResponse;
import io.opaa.asset.AssetCatalogEntry;
import io.opaa.asset.AssetCatalogPage;
import io.opaa.asset.AssetCatalogRow;
import io.opaa.permission.web.SuccessionStateResponseMapper;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Maps the catalog of {@code io.opaa.asset} onto its generated counterparts; the facts of a type go
 * through that type's {@link CatalogFactsResponseMapper}, and a type without one carries none.
 */
final class CatalogResponseMapper {

  private final Map<io.opaa.permission.AssetType, CatalogFactsResponseMapper> factsMappers;

  CatalogResponseMapper(Collection<CatalogFactsResponseMapper> factsMappers) {
    this.factsMappers =
        factsMappers.stream()
            .collect(Collectors.toMap(CatalogFactsResponseMapper::assetType, Function.identity()));
  }

  CatalogPageResponse toResponse(AssetCatalogPage page) {
    return new CatalogPageResponse(
        page.entries().stream().map(this::toResponse).toList(),
        page.page(),
        page.size(),
        page.totalElements(),
        page.totalPages());
  }

  CatalogEntryResponse toResponse(AssetCatalogEntry entry) {
    AssetCatalogRow asset = entry.asset();
    CatalogEntryResponse response =
        new CatalogEntryResponse(
                AssetType.fromValue(asset.getAssetType().value()),
                asset.getId(),
                asset.getName(),
                asset.getOwnerType(),
                asset.getOwnerId(),
                asset.getOrigin(),
                entry.visibility(),
                entry.myRole(),
                entry.status(),
                asset.getUpdatedAt(),
                entry.itemCount(),
                entry.spaceCount(),
                entry.favorite())
            .description(asset.getDescription())
            .ownerLabel(entry.ownerLabel())
            .succession(SuccessionStateResponseMapper.toStateResponse(entry.succession()));
    CatalogFactsResponseMapper factsMapper = factsMappers.get(asset.getAssetType());
    if (entry.facts() != null && factsMapper != null) {
      factsMapper.apply(entry.facts(), response);
    }
    return response;
  }
}
