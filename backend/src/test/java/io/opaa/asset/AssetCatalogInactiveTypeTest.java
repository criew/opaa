package io.opaa.asset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ValidationException;
import io.opaa.permission.AssetAccessService;
import io.opaa.permission.AssetType;
import io.opaa.permission.ReadableAssets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;

/**
 * The catalog over the active types only (ADR-0044): an inactive type is neither read nor offered
 * as a filter, so none of its assets appears, is counted or marks a favorite.
 */
class AssetCatalogInactiveTypeTest {

  private static final AssetType SHOWN = AssetType.of("SHOWN");
  private static final AssetType HIDDEN = AssetType.of("HIDDEN");

  private final AssetRepository assetRepository = mock(AssetRepository.class);
  private final AssetAccessService accessService = mock(AssetAccessService.class);
  private final AssetCatalogService catalog =
      new AssetCatalogService(
          assetRepository,
          accessService,
          new AssetTypes(
              List.of(
                  new AssetTypesTest.Definition(SHOWN, true),
                  new AssetTypesTest.Definition(HIDDEN, false))),
          mock(AssetOwnerNames.class),
          mock(AssetSuccessionSource.class),
          List.of(),
          List.of(),
          mock(AssetFavoriteRepository.class));

  private final CurrentUser caller =
      CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.USER, "Sachbearbeitung");

  @Test
  void theWholeCatalogReadsAndQueriesTheActiveTypesOnly() {
    UUID readable = UUID.randomUUID();
    when(accessService.readableAssets(eq(SHOWN), any(), any()))
        .thenReturn(new ReadableAssets(Set.of(readable), Set.of(), Set.of()));
    when(assetRepository.findCatalogPage(
            any(), any(), any(), anyString(), any(), anyBoolean(), any()))
        .thenReturn(Page.empty());

    catalog.list(caller, AssetCatalogQuery.of(null, null), 0, 50);

    verify(accessService, never()).readableAssets(eq(HIDDEN), any(), any());
    verify(assetRepository)
        .findCatalogPage(
            eq(caller.organizationId()),
            eq(List.of(SHOWN)),
            eq(Set.of(readable)),
            anyString(),
            eq(caller.id()),
            eq(false),
            any());
  }

  @Test
  void anInactiveTypeIsNoFilterLikeAnUnknownOne() {
    assertThatThrownBy(() -> catalog.list(caller, AssetCatalogQuery.of(HIDDEN, null), 0, 50))
        .isInstanceOf(ValidationException.class)
        .hasMessage("Unbekannter Asset-Typ: HIDDEN");
    verify(accessService, never()).readableAssets(any(), any(), any());
  }
}
