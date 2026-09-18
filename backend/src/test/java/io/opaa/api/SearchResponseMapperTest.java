package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.chat.ChatSourceMetadataEntry;
import io.opaa.query.SearchedLibraryRef;
import io.opaa.search.FetchedPassage;
import io.opaa.search.SearchHit;
import io.opaa.search.SearchOutcome;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchResponseMapperTest {

  private static final UUID DOCUMENT_ID = UUID.randomUUID();
  private static final UUID LIBRARY_ID = UUID.randomUUID();

  @Test
  void hitCarriesEveryFieldIncludingTheDownloadPathOfTheOriginal() {
    var outcome =
        new SearchOutcome(
            List.of(
                new SearchHit(
                    "chunk-1",
                    "Dienstanweisung Widerspruch",
                    "Die Frist beträgt einen Monat.",
                    LIBRARY_ID,
                    "Dienstanweisungen",
                    DOCUMENT_ID,
                    "dienstanweisung.pdf",
                    4,
                    "Abschn. Verfahren › Fristsetzung",
                    List.of(
                        new ChatSourceMetadataEntry(
                            "document_date",
                            "Datum/Stand",
                            "2024-03-01",
                            "03/2024",
                            MetadataOrigin.DETERMINISTIC,
                            DatePrecision.MONTH)),
                    1.0)),
            List.of(new SearchedLibraryRef(LIBRARY_ID, "Dienstanweisungen")));

    var response = SearchResponseMapper.toResponse(outcome);

    assertThat(response.getSearchedLibraries()).hasSize(1);
    assertThat(response.getSearchedLibraries().get(0).getId()).isEqualTo(LIBRARY_ID);
    assertThat(response.getSearchedLibraries().get(0).getName()).isEqualTo("Dienstanweisungen");
    var hit = response.getHits().get(0);
    assertThat(hit.getHitId()).isEqualTo("chunk-1");
    assertThat(hit.getTitle()).isEqualTo("Dienstanweisung Widerspruch");
    assertThat(hit.getExcerpt()).isEqualTo("Die Frist beträgt einen Monat.");
    assertThat(hit.getLibraryId()).isEqualTo(LIBRARY_ID);
    assertThat(hit.getLibraryName()).isEqualTo("Dienstanweisungen");
    assertThat(hit.getDocumentId()).isEqualTo(DOCUMENT_ID);
    assertThat(hit.getFileName()).isEqualTo("dienstanweisung.pdf");
    assertThat(hit.getChunkIndex()).isEqualTo(4);
    assertThat(hit.getLocation()).isEqualTo("Abschn. Verfahren › Fristsetzung");
    assertThat(hit.getRelevanceScore()).isEqualTo(1.0);
    assertThat(hit.getDownloadUrl()).isEqualTo("/api/v1/documents/" + DOCUMENT_ID + "/content");
    assertThat(hit.getMetadata()).hasSize(1);
    assertThat(hit.getMetadata().get(0).getFieldKey()).isEqualTo("document_date");
    assertThat(hit.getMetadata().get(0).getDisplayValue()).isEqualTo("03/2024");
    assertThat(hit.getMetadata().get(0).getDatePrecision()).isEqualTo(DatePrecision.MONTH);
  }

  @Test
  void hitWithoutADocumentCarriesNoDownloadPathAndNoMetadata() {
    var outcome =
        new SearchOutcome(
            List.of(
                new SearchHit(
                    "chunk-2",
                    "unbekannt.txt",
                    "Text",
                    null,
                    null,
                    null,
                    "unbekannt.txt",
                    null,
                    null,
                    null,
                    0.5)),
            List.of());

    var hit = SearchResponseMapper.toResponse(outcome).getHits().get(0);

    assertThat(hit.getDownloadUrl()).isNull();
    assertThat(hit.getMetadata()).isNull();
    assertThat(hit.getDocumentId()).isNull();
  }

  @Test
  void fetchedPassageCarriesEveryField() {
    var passage =
        new FetchedPassage(
            "chunk-1",
            DOCUMENT_ID,
            "dienstanweisung.pdf",
            "Dienstanweisung Widerspruch",
            LIBRARY_ID,
            "Dienstanweisungen",
            4,
            "Abschn. Verfahren › Fristsetzung",
            List.of("Verfahren", "Fristsetzung"),
            "Die Frist beträgt einen Monat.",
            true,
            true,
            200_000,
            List.of(
                new ChatSourceMetadataEntry(
                    "title",
                    "Titel",
                    "Dienstanweisung Widerspruch",
                    "Dienstanweisung Widerspruch",
                    MetadataOrigin.DETERMINISTIC,
                    null)));

    var response = SearchResponseMapper.toResponse(passage);

    assertThat(response.getHitId()).isEqualTo("chunk-1");
    assertThat(response.getDocumentId()).isEqualTo(DOCUMENT_ID);
    assertThat(response.getFileName()).isEqualTo("dienstanweisung.pdf");
    assertThat(response.getTitle()).isEqualTo("Dienstanweisung Widerspruch");
    assertThat(response.getLibraryId()).isEqualTo(LIBRARY_ID);
    assertThat(response.getLibraryName()).isEqualTo("Dienstanweisungen");
    assertThat(response.getChunkIndex()).isEqualTo(4);
    assertThat(response.getLocation()).isEqualTo("Abschn. Verfahren › Fristsetzung");
    assertThat(response.getHeadingPath()).containsExactly("Verfahren", "Fristsetzung");
    assertThat(response.getText()).isEqualTo("Die Frist beträgt einen Monat.");
    assertThat(response.getWhole()).isTrue();
    assertThat(response.getTruncated()).isTrue();
    assertThat(response.getCharacterLimit()).isEqualTo(200_000);
    assertThat(response.getMetadata()).hasSize(1);
    assertThat(response.getDownloadUrl())
        .isEqualTo("/api/v1/documents/" + DOCUMENT_ID + "/content");
  }
}
