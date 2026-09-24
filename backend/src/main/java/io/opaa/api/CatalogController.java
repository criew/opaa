package io.opaa.api;

import io.opaa.api.dto.AssetType;
import io.opaa.api.dto.CatalogPageResponse;
import io.opaa.asset.AssetCatalogService;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The catalog across every asset type - readable united with listed, one page at a time. */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

  private final AssetCatalogService catalogService;

  public CatalogController(AssetCatalogService catalogService) {
    this.catalogService = catalogService;
  }

  @GetMapping
  public CatalogPageResponse listCatalog(
      @RequestParam(required = false) AssetType type,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @Caller CurrentUser caller) {
    return CatalogResponseMapper.toResponse(
        catalogService.list(
            caller,
            type == null ? null : io.opaa.permission.AssetType.of(type.getValue()),
            q,
            page,
            size));
  }
}
