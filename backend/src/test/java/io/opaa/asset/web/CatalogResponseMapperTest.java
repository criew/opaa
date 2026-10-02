package io.opaa.asset.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.AssetType;
import io.opaa.api.dto.CatalogEntryResponse;
import io.opaa.api.dto.CatalogEntryStatus;
import io.opaa.api.dto.CatalogKnowledgeLibraryFacts;
import io.opaa.api.dto.CatalogPageResponse;
import io.opaa.api.dto.CatalogVisibility;
import io.opaa.api.types.AssetOrigin;
import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.SuccessionAddressee;
import io.opaa.asset.AssetCatalogEntry;
import io.opaa.asset.AssetCatalogFacts;
import io.opaa.asset.AssetCatalogPage;
import io.opaa.asset.AssetCatalogRow;
import io.opaa.asset.AssetCatalogStatus;
import io.opaa.asset.AssetCatalogVisibility;
import io.opaa.permission.SuccessionFinding;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CatalogResponseMapperTest {

  private static final UUID ASSET_ID = UUID.randomUUID();
  private static final UUID OWNER_ID = UUID.randomUUID();
  private static final Instant UPDATED_AT = Instant.parse("2026-09-30T08:15:00Z");
  private static final io.opaa.permission.AssetType PROMPTS =
      io.opaa.permission.AssetType.of("PROMPT_LIBRARY");

  private record TestFacts(AssetCatalogStatus status, String label) implements AssetCatalogFacts {}

  /** Writes the label of {@link TestFacts} where a knowledge library's source type goes. */
  private static final CatalogFactsResponseMapper TEST_FACTS_MAPPER =
      new CatalogFactsResponseMapper() {
        @Override
        public io.opaa.permission.AssetType assetType() {
          return PROMPTS;
        }

        @Override
        public void apply(AssetCatalogFacts facts, CatalogEntryResponse response) {
          response.knowledgeLibrary(new CatalogKnowledgeLibraryFacts(((TestFacts) facts).label()));
        }
      };

  private final CatalogResponseMapper mapper = new CatalogResponseMapper(List.of());

  @Test
  void everyFieldOfAnEntryAndThePageIsCarried() {
    AssetCatalogEntry entry =
        new AssetCatalogEntry(
            row(AssetOrigin.BUILT_IN),
            AssetRole.EDITOR,
            AssetCatalogVisibility.PUBLIC,
            AssetCatalogStatus.SUCCESSION_OPEN,
            null,
            "Referat 50",
            SuccessionFinding.ofAsset(
                PROMPTS, ASSET_ID, "Vorlagen", SuccessionAddressee.GROUP_STEWARDS),
            7,
            3);

    CatalogPageResponse page =
        mapper.toResponse(new AssetCatalogPage(List.of(entry), 2, 10, 21, 3));

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
    assertThat(response.getOwnerId()).isEqualTo(OWNER_ID);
    assertThat(response.getOwnerLabel()).isEqualTo("Referat 50");
    assertThat(response.getOrigin()).isEqualTo(AssetOrigin.BUILT_IN);
    assertThat(response.getVisibility()).isEqualTo(CatalogVisibility.PUBLIC);
    assertThat(response.getMyRole()).isEqualTo(AssetRole.EDITOR);
    assertThat(response.getStatus()).isEqualTo(CatalogEntryStatus.SUCCESSION_OPEN);
    assertThat(response.getUpdatedAt()).isEqualTo(UPDATED_AT);
    assertThat(response.getItemCount()).isEqualTo(7);
    assertThat(response.getSpaceCount()).isEqualTo(3);
    assertThat(response.getSuccession()).isNotNull();
    assertThat(response.getSuccession().getAddressee())
        .isEqualTo(SuccessionAddressee.GROUP_STEWARDS);
    assertThat(response.getKnowledgeLibrary()).as("no facts, no type property").isNull();
  }

  @Test
  void anEntryWithoutSuccessionOrOwnerLabelCarriesNeither() {
    CatalogEntryResponse response = mapper.toResponse(entry(AssetCatalogStatus.READY, null));

    assertThat(response.getOwnerLabel()).isNull();
    assertThat(response.getSuccession()).isNull();
    assertThat(response.getOrigin()).isEqualTo(AssetOrigin.LOCAL);
    assertThat(response.getVisibility()).isEqualTo(CatalogVisibility.RESTRICTED);
  }

  @Test
  void theFactsOfATypeGoThroughThatTypesMapper() {
    CatalogResponseMapper withFacts = new CatalogResponseMapper(List.of(TEST_FACTS_MAPPER));
    TestFacts facts = new TestFacts(AssetCatalogStatus.UPDATING, "FILESYSTEM");

    CatalogEntryResponse response = withFacts.toResponse(entry(AssetCatalogStatus.UPDATING, facts));

    assertThat(response.getKnowledgeLibrary().getSourceType()).isEqualTo("FILESYSTEM");
    assertThat(response.getStatus()).isEqualTo(CatalogEntryStatus.UPDATING);
  }

  @Test
  void factsOfATypeWithoutAMapperAreLeftOut() {
    CatalogEntryResponse response =
        mapper.toResponse(
            entry(AssetCatalogStatus.READY, new TestFacts(AssetCatalogStatus.READY, "x")));

    assertThat(response.getKnowledgeLibrary()).isNull();
  }

  @ParameterizedTest
  @EnumSource(AssetCatalogStatus.class)
  void everyStatusHasItsCounterpartInTheSpecification(AssetCatalogStatus status) {
    assertThat(mapper.toResponse(entry(status, null)).getStatus().name()).isEqualTo(status.name());
  }

  @Test
  void theSpecificationNamesNoStatusOrVisibilityTheDomainLacks() {
    assertThat(Arrays.stream(CatalogEntryStatus.values()).map(Enum::name))
        .containsExactlyInAnyOrderElementsOf(
            Arrays.stream(AssetCatalogStatus.values()).map(Enum::name).toList());
    assertThat(Arrays.stream(CatalogVisibility.values()).map(Enum::name))
        .containsExactlyInAnyOrderElementsOf(
            Arrays.stream(AssetCatalogVisibility.values()).map(Enum::name).toList());
  }

  private static AssetCatalogEntry entry(AssetCatalogStatus status, AssetCatalogFacts facts) {
    return new AssetCatalogEntry(
        row(AssetOrigin.LOCAL),
        AssetRole.VIEWER,
        AssetCatalogVisibility.RESTRICTED,
        status,
        facts,
        null,
        null,
        0,
        0);
  }

  private static AssetCatalogRow row(AssetOrigin origin) {
    return new AssetCatalogRow() {
      @Override
      public UUID getId() {
        return ASSET_ID;
      }

      @Override
      public io.opaa.permission.AssetType getAssetType() {
        return PROMPTS;
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
      public AssetOrigin getOrigin() {
        return origin;
      }

      @Override
      public Instant getUpdatedAt() {
        return UPDATED_AT;
      }
    };
  }
}
