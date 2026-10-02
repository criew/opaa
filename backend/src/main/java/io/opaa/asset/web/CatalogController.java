package io.opaa.asset.web;

import io.opaa.api.dto.AssetType;
import io.opaa.api.dto.CatalogPageResponse;
import io.opaa.api.types.CatalogVisibility;
import io.opaa.asset.AssetCatalogQuery;
import io.opaa.asset.AssetCatalogService;
import io.opaa.asset.AssetCatalogSort;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ValidationException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The catalog across every asset type - only what the caller may read, one page at a time. */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

  private final AssetCatalogService catalogService;
  private final CatalogResponseMapper responseMapper;

  public CatalogController(
      AssetCatalogService catalogService, List<CatalogFactsResponseMapper> factsMappers) {
    this.catalogService = catalogService;
    this.responseMapper = new CatalogResponseMapper(factsMappers);
  }

  @GetMapping
  public CatalogPageResponse listCatalog(
      @RequestParam(required = false) AssetType type,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) CatalogVisibility visibility,
      @RequestParam(defaultValue = "false") boolean fromMyGroups,
      @RequestParam(defaultValue = "false") boolean favorites,
      @RequestParam(defaultValue = "name") String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @Caller CurrentUser caller) {
    AssetCatalogQuery query =
        new AssetCatalogQuery(
            type == null ? null : io.opaa.permission.AssetType.of(type.getValue()),
            q,
            visibility,
            fromMyGroups,
            sortOf(sort),
            favorites);
    return responseMapper.toResponse(catalogService.list(caller, query, page, size));
  }

  private static AssetCatalogSort sortOf(String sort) {
    return switch (sort) {
      case "name" -> AssetCatalogSort.NAME;
      case "updatedAt" -> AssetCatalogSort.UPDATED_AT;
      default -> throw new ValidationException("Unbekannte Sortierung: " + sort);
    };
  }
}
