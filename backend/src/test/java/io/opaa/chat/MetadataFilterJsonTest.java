package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.metadata.LibraryFieldCondition;
import io.opaa.indexing.metadata.MetadataFilter;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The chat's persisted filter reads back as the record it was written from (#1070). */
class MetadataFilterJsonTest {

  @Test
  void roundTripsEveryField() {
    MetadataFilter filter =
        new MetadataFilter(
            Set.of("VERMERK", "DIENSTANWEISUNG"),
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 12, 31));

    String json = MetadataFilterJson.write(filter);

    assertThat(json)
        .isEqualTo(
            "{\"documentTypes\":[\"DIENSTANWEISUNG\",\"VERMERK\"],\"documentDateFrom\":\"2024-01-01\","
                + "\"documentDateTo\":\"2024-12-31\"}");
    assertThat(MetadataFilterJson.read(json)).isEqualTo(filter);
  }

  /** A library-field condition survives the round trip with its library, its type and its shape. */
  @Test
  void roundTripsALibraryFieldCondition() {
    UUID libraryId = UUID.randomUUID();
    MetadataFilter filter =
        MetadataFilter.NONE.withLibraryFields(
            List.of(
                LibraryFieldCondition.ofCodes(libraryId, "fassung", List.of("F2026")),
                LibraryFieldCondition.ofValue(libraryId, "paragraf", "§ 7")));

    String json = MetadataFilterJson.write(filter);

    assertThat(json).contains("\"libraryFields\"").contains(libraryId.toString());
    assertThat(MetadataFilterJson.read(json)).isEqualTo(filter);
  }

  @Test
  void anEmptyFilterIsStoredAsNullAndReadBackAsNone() {
    assertThat(MetadataFilterJson.write(MetadataFilter.NONE)).isNull();
    assertThat(MetadataFilterJson.write(null)).isNull();
    assertThat(MetadataFilterJson.read(null)).isEqualTo(MetadataFilter.NONE);
    assertThat(
            MetadataFilterJson.read(
                MetadataFilterJson.write(MetadataFilter.ofDocumentTypes(List.of("VERMERK")))))
        .isEqualTo(MetadataFilter.ofDocumentTypes(List.of("VERMERK")));
  }
}
