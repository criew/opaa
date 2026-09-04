package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DatePrecision;
import io.opaa.indexing.pipeline.DocumentProperties;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The deterministic rules of metadata-schema.md, Teil III step 1 (#1066): file-name conventions,
 * German date notations, the vocabulary boundary and the title source order - every case pinned
 * against the delivered vocabulary, no model, no similarity.
 */
class CoreMetadataExtractorTest {

  private final DocumentTypeVocabulary vocabulary = TestVocabularies.delivered();

  private ExtractedCoreMetadata extract(String fileName, DocumentProperties properties) {
    return CoreMetadataExtractor.extract(fileName, properties, vocabulary);
  }

  @Nested
  class FileNameConventions {

    @Test
    void isoDateAndDocumentTypeTokenCarryTwoFields() {
      ExtractedCoreMetadata result =
          extract("2026-03-12_Dienstanweisung_IT-Nutzung.pdf", DocumentProperties.EMPTY);

      assertThat(result.documentTypeCode()).contains("DIENSTANWEISUNG");
      assertThat(result.date()).contains(ExtractedDate.day(LocalDate.of(2026, 3, 12)));
      assertThat(result.title()).contains("Dienstanweisung IT Nutzung");
    }

    @Test
    void germanDateNotationIsRecognized() {
      ExtractedCoreMetadata result =
          extract("Vermerk vom 12.03.2026.docx", DocumentProperties.EMPTY);

      assertThat(result.date()).contains(ExtractedDate.day(LocalDate.of(2026, 3, 12)));
      assertThat(result.documentTypeCode()).contains("VERMERK");
    }

    @Test
    void bareYearCountsOnlyAsAStandaloneToken() {
      assertThat(extract("Haushaltsplan_2024.pdf", DocumentProperties.EMPTY).date())
          .contains(ExtractedDate.year(2024));
      assertThat(extract("Bericht_12345.pdf", DocumentProperties.EMPTY).date()).isEmpty();
      assertThat(extract("Az_2024-123.pdf", DocumentProperties.EMPTY).date()).isEmpty();
      assertThat(extract("Muster_20240312.pdf", DocumentProperties.EMPTY).date()).isEmpty();
    }

    @Test
    void isoMonthYieldsMonthPrecision() {
      assertThat(extract("Sitzung_2025-11.md", DocumentProperties.EMPTY).date())
          .contains(ExtractedDate.month(2025, 11));
    }

    @Test
    void invalidCalendarDateIsNotADate() {
      assertThat(extract("Protokoll_31.02.2026.pdf", DocumentProperties.EMPTY).date()).isEmpty();
    }

    @Test
    void documentTypeMatchesCodeLabelAndSynonymCaseAndUmlautInsensitively() {
      assertThat(
              extract("GEBÜHRENVERZEICHNIS_Stadt.pdf", DocumentProperties.EMPTY).documentTypeCode())
          .contains("GEBUEHRENVERZEICHNIS");
      assertThat(extract("niederschrift-rat.pdf", DocumentProperties.EMPTY).documentTypeCode())
          .contains("PROTOKOLL");
      assertThat(extract("Praesentation_Q1.pptx", DocumentProperties.EMPTY).documentTypeCode())
          .contains("PRAESENTATION");
      assertThat(extract("DA_Homeoffice.pdf", DocumentProperties.EMPTY).documentTypeCode())
          .contains("DIENSTANWEISUNG");
    }

    @Test
    void aTokenOutsideTheVocabularyLeavesTheFieldEmptyNeverTheNearestValue() {
      assertThat(
              extract("Dienstanweisungsentwurf.pdf", DocumentProperties.EMPTY).documentTypeCode())
          .isEmpty();
      assertThat(extract("Rundschreiben_2024.pdf", DocumentProperties.EMPTY).documentTypeCode())
          .isEmpty();
    }

    @Test
    void twoDifferentDocumentTypeTokensLeaveTheFieldEmpty() {
      assertThat(
              extract("Protokoll_zur_Dienstanweisung.pdf", DocumentProperties.EMPTY)
                  .documentTypeCode())
          .isEmpty();
    }
  }

  @Nested
  class TitleSourceOrder {

    @Test
    void formatTitlePropertyWinsOverHeadingAndFileName() {
      DocumentProperties properties =
          DocumentProperties.EMPTY.withTitle("Eigenschaftstitel").withFirstHeading("Überschrift");

      assertThat(extract("001_datei.pdf", properties).title()).contains("Eigenschaftstitel");
    }

    @Test
    void firstHeadingWinsOverFileName() {
      DocumentProperties properties = DocumentProperties.EMPTY.withFirstHeading("Überschrift");

      assertThat(extract("001_datei.pdf", properties).title()).contains("Überschrift");
    }

    @Test
    void frontmatterTitleRanksAsAPropertyAboveTheHeading() {
      DocumentProperties properties =
          DocumentProperties.EMPTY
              .withFrontmatter(Map.of("titel", "\"Sozialgebührenbefreiungssatzung\""))
              .withFirstHeading("Überschrift");

      assertThat(extract("verwaltung-0001.md", properties).title())
          .contains("Sozialgebührenbefreiungssatzung");
    }

    @Test
    void fileNameIsTheHumanizedFallback() {
      assertThat(extract("001_personalausweis.md", DocumentProperties.EMPTY).title())
          .contains("personalausweis");
    }
  }

  @Nested
  class Frontmatter {

    @Test
    void corpusKeysAreReadExactlyAgainstTheVocabulary() {
      DocumentProperties properties =
          DocumentProperties.EMPTY.withFrontmatter(
              Map.of(
                  "dokumentart",
                  "\"satzung\"",
                  "stand_datum",
                  "\"2024-01-01\"",
                  "fassung",
                  "2024"));

      ExtractedCoreMetadata result = extract("verwaltung-0002_fassung-2024.md", properties);

      assertThat(result.documentTypeCode()).contains("SATZUNG_ORDNUNG");
      assertThat(result.date()).contains(ExtractedDate.day(LocalDate.of(2024, 1, 1)));
    }

    @Test
    void fassungAloneYieldsYearPrecision() {
      DocumentProperties properties =
          DocumentProperties.EMPTY.withFrontmatter(Map.of("fassung", "2023"));

      ExtractedCoreMetadata result = extract("satzung.md", properties);

      assertThat(result.date()).contains(ExtractedDate.year(2023));
      assertThat(result.date().orElseThrow().precision()).isEqualTo(DatePrecision.YEAR);
    }

    @Test
    void aDeclaredDocumentTypeOutsideTheVocabularyStaysEmptyEvenIfTheFileNameWouldMatch() {
      DocumentProperties properties =
          DocumentProperties.EMPTY.withFrontmatter(Map.of("dokumentart", "\"formularhinweis\""));

      assertThat(extract("Vermerk_formularhinweis.md", properties).documentTypeCode()).isEmpty();
    }
  }

  @Nested
  class DateSourceOrder {

    @Test
    void theFormatsOwnDocumentDateBeatsHeadingFileNameAndProperties() {
      DocumentProperties properties =
          DocumentProperties.EMPTY
              .withDocumentDate(LocalDate.of(2026, 3, 14))
              .withFirstHeading("Vermerk vom 01.01.2020")
              .withModifiedAt(LocalDate.of(2025, 1, 1));

      assertThat(extract("2019-01-01_mail.eml", properties).date())
          .contains(ExtractedDate.day(LocalDate.of(2026, 3, 14)));
    }

    @Test
    void headingBeatsFileNameBeatsModifiedBeatsCreated() {
      DocumentProperties heading =
          DocumentProperties.EMPTY
              .withFirstHeading("Dienstanweisung Stand März 2026")
              .withModifiedAt(LocalDate.of(2025, 1, 1))
              .withCreatedAt(LocalDate.of(2024, 1, 1));
      assertThat(extract("2019-01-01_da.pdf", heading).date())
          .contains(ExtractedDate.month(2026, 3));

      DocumentProperties noHeading =
          DocumentProperties.EMPTY
              .withModifiedAt(LocalDate.of(2025, 1, 1))
              .withCreatedAt(LocalDate.of(2024, 1, 1));
      assertThat(extract("2019-01-01_da.pdf", noHeading).date())
          .contains(ExtractedDate.day(LocalDate.of(2019, 1, 1)));
      assertThat(extract("da.pdf", noHeading).date())
          .contains(ExtractedDate.day(LocalDate.of(2025, 1, 1)));
      assertThat(extract("da.pdf", noHeading.withModifiedAt(null)).date())
          .contains(ExtractedDate.day(LocalDate.of(2024, 1, 1)));
    }

    @Test
    void nothingDeclaredMeansNoDate() {
      assertThat(extract("da.pdf", DocumentProperties.EMPTY).date()).isEmpty();
    }
  }

  @Test
  void anEmptyVocabularyNeverYieldsADocumentType() {
    ExtractedCoreMetadata result =
        CoreMetadataExtractor.extract(
            "Dienstanweisung.pdf", DocumentProperties.EMPTY, DocumentTypeVocabulary.empty());

    assertThat(result.documentTypeCode()).isEmpty();
  }
}
