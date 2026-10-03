package io.opaa.asset.web;

import io.opaa.api.dto.AssetType;
import io.opaa.api.dto.CatalogPageResponse;
import io.opaa.asset.AssetCatalogQuery;
import io.opaa.asset.AssetCatalogService;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ValidationException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
      @RequestParam(defaultValue = "false") boolean favorites,
      @RequestParam(required = false) List<UUID> ids,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @Caller CurrentUser caller) {
    // An empty "ids=" binds as null; it names no asset and is refused like a malformed id.
    if (ids != null && ids.stream().anyMatch(Objects::isNull)) {
      throw new ValidationException("ids darf keine leeren Kennungen enthalten");
    }
    AssetCatalogQuery query =
        new AssetCatalogQuery(
            type == null ? null : io.opaa.permission.AssetType.of(type.getValue()),
            q,
            favorites,
            ids == null ? null : Set.copyOf(ids));
    return responseMapper.toResponse(catalogService.list(caller, query, page, size));
  }
}
