package io.opaa.library.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.CatalogEntryResponse;
import io.opaa.api.types.CatalogEntryStatus;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryCatalogFacts;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class KnowledgeLibraryCatalogFactsMapperTest {

  private final KnowledgeLibraryCatalogFactsMapper mapper =
      new KnowledgeLibraryCatalogFactsMapper();

  @Test
  void theFactsOfAKnowledgeLibraryLandInItsOwnProperty() {
    Instant indexedAt = Instant.parse("2026-09-20T06:00:00Z");
    CatalogEntryResponse response = new CatalogEntryResponse();

    mapper.apply(
        new KnowledgeLibraryCatalogFacts("CONFLUENCE", indexedAt, CatalogEntryStatus.READY),
        response);

    assertThat(mapper.assetType()).isEqualTo(KnowledgeLibrary.ASSET_TYPE);
    assertThat(response.getKnowledgeLibrary().getSourceType()).isEqualTo("CONFLUENCE");
    assertThat(response.getKnowledgeLibrary().getLastIndexedAt()).isEqualTo(indexedAt);
  }

  @Test
  void aLibraryNeverIndexedHasNoStand() {
    CatalogEntryResponse response = new CatalogEntryResponse();

    mapper.apply(
        new KnowledgeLibraryCatalogFacts("UPLOAD", null, CatalogEntryStatus.READY), response);

    assertThat(response.getKnowledgeLibrary().getLastIndexedAt()).isNull();
  }

  @Test
  void aLockedLibraryCarriesItsNotice() {
    CatalogEntryResponse response = new CatalogEntryResponse();

    mapper.apply(
        new KnowledgeLibraryCatalogFacts(
            "CONFLUENCE", null, CatalogEntryStatus.READY, "Gesperrt – Inhalt wird nicht mehr …"),
        response);

    assertThat(response.getKnowledgeLibrary().getSourceLockNotice())
        .isEqualTo("Gesperrt – Inhalt wird nicht mehr …");
  }
}
