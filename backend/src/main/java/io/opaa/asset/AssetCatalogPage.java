package io.opaa.asset;

import java.util.List;

/** One page of the catalog, ordered by name. */
public record AssetCatalogPage(
    List<AssetCatalogEntry> entries, int page, int size, long totalElements, int totalPages) {}
