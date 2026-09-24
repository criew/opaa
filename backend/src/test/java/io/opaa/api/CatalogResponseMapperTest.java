package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.AssetOrigin;
import io.opaa.api.dto.AssetType;
import io.opaa.api.dto.CatalogEntryResponse;
import io.opaa.api.dto.CatalogPageResponse;
import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.SuccessionAddressee;
import io.opaa.asset.AssetCatalogEntry;
import io.opaa.asset.AssetCatalogPage;
import io.opaa.asset.AssetCatalogRow;
import io.opaa.permission.SuccessionFinding;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogResponseMapperTest {

  private static final UUID ASSET_ID = UUID.randomUUID();
  private static final UUID OWNER_ID = UUID.randomUUID();

  @Test
  void everyFieldOfAnEntryAndThePageIsCarried() {
    AssetCatalogEntry entry =
        new AssetCatalogEntry(
            row(io.opaa.asset.AssetOrigin.BUILT_IN),
            false,
            "Referat 50",
            SuccessionFinding.ofAsset(
                io.opaa.permission.AssetType.of("PROMPT_LIBRARY"),
                ASSET_ID,
                "Vorlagen",
                SuccessionAddressee.GROUP_STEWARDS));

    CatalogPageResponse page =
        CatalogResponseMapper.toResponse(new AssetCatalogPage(List.of(entry), 2, 10, 21, 3));

    assertThat(page.getPage()).isEqualTo(2);
    assertThat(page.getSize()).isEqualTo(10);
    assertThat(page.getTotalElements()).isEqualTo(21);
    assertThat(page.getTotalPages()).isEqualTo(3);
    CatalogEntryResponse response = page.getEntries().getFirst();
    assertThat(response.getAssetType()).isEqualTo(AssetType.PROMPT_LIBRARY);
    assertThat(response.getAssetId()).isEqualTo(ASSET_ID);
    assertThat(response.getName()).isEqualTo("Vorlagen");
    assertThat(response.getDescription()).isEqualTo("Hausstandard");
    assertThat(response.getOwnerType()).isEqualTo(AssetOwnerType.GROUP);
    assertThat(response.getOwnerLabel()).isEqualTo("Referat 50");
    assertThat(response.getOrigin()).isEqualTo(AssetOrigin.BUILT_IN);
    assertThat(response.getAccessible()).isFalse();
    assertThat(response.getListed()).isTrue();
    assertThat(response.getSuccession()).isNotNull();
    assertThat(response.getSuccession().getAddressee())
        .isEqualTo(SuccessionAddressee.GROUP_STEWARDS);
  }

  @Test
  void anEntryWithoutSuccessionOrOwnerLabelCarriesNeither() {
    CatalogEntryResponse response =
        CatalogResponseMapper.toResponse(
            new AssetCatalogEntry(row(io.opaa.asset.AssetOrigin.LOCAL), true, null, null));

    assertThat(response.getOwnerLabel()).isNull();
    assertThat(response.getSuccession()).isNull();
    assertThat(response.getOrigin()).isEqualTo(AssetOrigin.LOCAL);
    assertThat(response.getAccessible()).isTrue();
  }

  private static AssetCatalogRow row(io.opaa.asset.AssetOrigin origin) {
    return new AssetCatalogRow() {
      @Override
      public UUID getId() {
        return ASSET_ID;
      }

      @Override
      public io.opaa.permission.AssetType getAssetType() {
        return io.opaa.permission.AssetType.of("PROMPT_LIBRARY");
      }

      @Override
      public String getName() {
        return "Vorlagen";
      }

      @Override
      public AssetOwnerType getOwnerType() {
        return AssetOwnerType.GROUP;
      }

      @Override
      public UUID getOwnerUserId() {
        return null;
      }

      @Override
      public UUID getOwnerGroupId() {
        return OWNER_ID;
      }

      @Override
      public String getDescription() {
        return "Hausstandard";
      }

      @Override
      public io.opaa.asset.AssetOrigin getOrigin() {
        return origin;
      }

      @Override
      public boolean isListed() {
        return true;
      }
    };
  }
}
