package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DatePrecision;
import io.opaa.indexing.pipeline.DocumentProperties;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The deterministic rules of metadata-schema.md, Teil III step 1: file-name conventions, German
 * date notations, the vocabulary boundary and the title source order - every case pinned against
 * the delivered vocabulary, no model, no similarity.
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

    // An invalid candidate (an Aktenzeichen that looks like a date, an impossible ISO
    // month) must not end the search - the next candidate of the same notation still counts.
    @Test
    void anInvalidCandidateBeforeAValidDateIsSkipped() {
      assertThat(
              extract("Az_12.34.5678_Vermerk_vom_05.03.2026.pdf", DocumentProperties.EMPTY).date())
          .contains(ExtractedDate.day(LocalDate.of(2026, 3, 5)));
      assertThat(
              extract("Bericht_2026-13-01_Stand_2026-03-05.pdf", DocumentProperties.EMPTY).date())
          .contains(ExtractedDate.day(LocalDate.of(2026, 3, 5)));
    }

    // No two-letter synonym - a lower-cased "DA" is indistinguishable from the German
    // filler word, and a second code would empty an otherwise unambiguous Dokumentart.
    @Test
    void theFillerWordDaNeverCountsAsADokumentart() {
      assertThat(
              extract("Vermerk da Termin verschoben.pdf", DocumentProperties.EMPTY)
                  .documentTypeCode())
          .contains("VERMERK");
      assertThat(extract("DA_Homeoffice.pdf", DocumentProperties.EMPTY).documentTypeCode())
          .isEmpty();
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

  /** the Kompositum ending rule seeded per vocabulary value in migration 020. */
  @Nested
  class KompositumEndings {

    @Test
    void aTokenEndingOnASeededSuffixDenotesThatDokumentart() {
      assertThat(
              extract("01_verwaltungsgebuehrensatzung.pdf", DocumentProperties.EMPTY)
                  .documentTypeCode())
          .contains("SATZUNG_ORDNUNG");
      assertThat(
              extract("Friedhofsgebuehrenordnung.pdf", DocumentProperties.EMPTY).documentTypeCode())
          .contains("SATZUNG_ORDNUNG");
      assertThat(
              extract("Verwaltungsgebührenverzeichnis.pdf", DocumentProperties.EMPTY)
                  .documentTypeCode())
          .contains("GEBUEHRENVERZEICHNIS");
      assertThat(extract("Rahmendienstanweisung.pdf", DocumentProperties.EMPTY).documentTypeCode())
          .contains("DIENSTANWEISUNG");
    }

    @Test
    void aTooShortPrefixInFrontOfTheEndingNeverCounts() {
      // "Anordnung" is an Anordnung, not an Ordnung: two characters in front of the ending are
      // below the seeded minimum, and the seed lists the token as an exclusion on top of that.
      assertThat(extract("Anordnung_Streugut.pdf", DocumentProperties.EMPTY).documentTypeCode())
          .isEmpty();
      assertThat(extract("Zuordnung_Aktenzeichen.pdf", DocumentProperties.EMPTY).documentTypeCode())
          .isEmpty();
    }

    @Test
    void aSeededExclusionIsNeverClaimedByItsEnding() {
      // Frequent administrative compounds that are no Dokumentart; no length rule separates them
      // from "Verordnung" or "Hausordnung", so the seed names them one by one.
      for (String fileName :
          List.of(
              "Einordnung_Rechtslage.pdf",
              "Neuordnung_Aemter.pdf",
              "Tagesordnung_Ratssitzung.pdf",
              "Groessenordnung_Beschaffung.pdf",
              "Größenordnung_Beschaffung.pdf",
              "Sitzordnung_Ratssaal.pdf",
              "Rangordnung.pdf",
              "Sperrvermerk_Haushalt.pdf",
              "Eingangsvermerk.pdf")) {
        assertThat(extract(fileName, DocumentProperties.EMPTY).documentTypeCode())
            .as(fileName)
            .isEmpty();
      }
    }

    @Test
    void aCompoundThatIsGenuinelyARechtsnormStaysAdmitted() {
      assertThat(extract("Hausordnung_Rathaus.pdf", DocumentProperties.EMPTY).documentTypeCode())
          .contains("SATZUNG_ORDNUNG");
      assertThat(extract("Hundesteuerverordnung.pdf", DocumentProperties.EMPTY).documentTypeCode())
          .contains("SATZUNG_ORDNUNG");
    }

    @Test
    void anEndingNeverBeatsAnExactVocabularyTerm() {
      // "dienstanordnung" is a seeded synonym of DIENSTANWEISUNG; the ending "-ordnung" would
      // otherwise make it a Satzung/Ordnung.
      assertThat(extract("Dienstanordnung_IT.pdf", DocumentProperties.EMPTY).documentTypeCode())
          .contains("DIENSTANWEISUNG");
    }
  }

  /**
   * the title line as the third source of the Dokumentart - the first heading, else the first line
   * of the text, and nothing below it.
   */
  @Nested
  class TitleLineSource {

    @Test
    void theFirstHeadingNamesTheDokumentartWhenTheFileNameDoesNot() {
      DocumentProperties properties =
          DocumentProperties.EMPTY.withFirstHeading("Dienstanweisung Nr. 1 - Identitaetszweifel");

      assertThat(extract("01_identitaetszweifel-ausweisantrag.docx", properties).documentTypeCode())
          .contains("DIENSTANWEISUNG");
    }

    @Test
    void theTitleLineOfTheBodyTextCountsWhenThereIsNoHeading() {
      assertThat(
              extract(
                      "anlage.pdf",
                      DocumentProperties.EMPTY.withTitleLine(
                          "Niederschrift ueber die Sitzung des Rates"))
                  .documentTypeCode())
          .contains("PROTOKOLL");
    }

    @Test
    void aWordBeyondTheTitleLineLimitNeverBecomesADokumentart() {
      String longLead = "Sehr geehrte Damen und Herren, ".repeat(20);
      DocumentProperties properties =
          DocumentProperties.EMPTY.withTitleLine(longLead + "Protokoll der Sitzung");

      assertThat(properties.titleLine())
          .as("DocumentProperties cuts the title line itself, so the word is no longer in it")
          .doesNotContain("Protokoll");
      assertThat(extract("anlage.pdf", properties).documentTypeCode()).isEmpty();
    }

    @Test
    void theKompositumEndingNeverAppliesToRunningText() {
      // The ending rule is for file names; running text is full of compounds that are no
      // Dokumentart, and a wrong DETERMINISTIC value is the damage the Leitregel excludes.
      for (String head :
          List.of(
              "Beschaffungen in dieser Größenordnung beduerfen der Zustimmung des Rates.",
              "Die Tagesordnung wird zu Beginn der Sitzung festgestellt.",
              "Der Vorgang traegt einen Sperrvermerk.",
              // Not "Gebuehrensatzung": that one is a seeded synonym and therefore an exact
              // vocabulary term, which the title line is allowed to resolve.
              "Diese Verwaltungsgebuehrensatzung wurde am 12.03.2026 beschlossen.")) {
        assertThat(
                extract("anlage.pdf", DocumentProperties.EMPTY.withTitleLine(head))
                    .documentTypeCode())
            .as(head)
            .isEmpty();
      }
      // An exact vocabulary term in the title line still counts - that is the source's whole
      // purpose.
      assertThat(
              extract(
                      "anlage.pdf",
                      DocumentProperties.EMPTY.withTitleLine(
                          "Satzung ueber die Erhebung von" + " Gebuehren"))
                  .documentTypeCode())
          .contains("SATZUNG_ORDNUNG");
    }

    @Test
    void theTitleLineIsCutAtAWordBoundarySoNoFragmentEverMatches() {
      // The limit falls exactly behind "Gebührensatzung" inside "Gebührensatzungsentwurf" - a hard
      // cut would turn the fragment into a seeded synonym and yield a DETERMINISTIC value.
      String lead = "a".repeat(DocumentProperties.MAX_TITLE_LINE_LENGTH - 16) + " ";
      DocumentProperties properties =
          DocumentProperties.EMPTY.withTitleLine(lead + "Gebührensatzungsentwurf liegt vor");

      assertThat(properties.titleLine()).doesNotContain("ebühren");
      assertThat(extract("anlage.pdf", properties).documentTypeCode()).isEmpty();
    }

    @Test
    void onlyWholeWordsMatch() {
      assertThat(
              extract(
                      "anlage.pdf",
                      DocumentProperties.EMPTY.withTitleLine("Vermerkzettel und Protokollanten"))
                  .documentTypeCode())
          .isEmpty();
    }

    @Test
    void theFileNameOutranksTheTitleLine() {
      DocumentProperties properties = DocumentProperties.EMPTY.withFirstHeading("Vermerk");

      assertThat(extract("Protokoll_Sitzung.pdf", properties).documentTypeCode())
          .contains("PROTOKOLL");
    }

    @Test
    void anAmbiguousFileNameStillLetsTheTitleLineDecide() {
      // Unlike the frontmatter declaration, an ambiguous file name is no statement about the
      // document - it yields nothing, and the next source is still asked.
      DocumentProperties properties = DocumentProperties.EMPTY.withFirstHeading("Vermerk");

      assertThat(extract("Protokoll_zur_Dienstanweisung.pdf", properties).documentTypeCode())
          .contains("VERMERK");
    }

    @Test
    void twoDifferentDokumentartenInTheTitleLineLeaveTheFieldEmpty() {
      DocumentProperties properties =
          DocumentProperties.EMPTY.withFirstHeading("Protokoll zur Dienstanweisung vom 12.03.2026");

      assertThat(extract("anlage.pdf", properties).documentTypeCode()).isEmpty();
    }

    @Test
    void aFirstHeadingContradictingTheTitleLineLeavesTheFieldEmpty() {
      DocumentProperties properties =
          DocumentProperties.EMPTY
              .withFirstHeading("Protokoll")
              .withTitleLine("Anlage zur Dienstanweisung vom 12.03.2026");

      assertThat(extract("anlage.pdf", properties).documentTypeCode()).isEmpty();
    }

    @Test
    void aLabelLineBelowTheTitleNeverNamesTheDokumentart() {
      // The demo's Leistungsbeschreibungen: the head names the Formular the service needs.
      DocumentProperties properties =
          DocumentProperties.EMPTY
              .withFirstHeading("Fabrikneues Fahrzeug anmelden")
              .withTitleLine(
                  "Fabrikneues Fahrzeug anmelden\nFormular: RF-KFZ-001\nAktenzeichen: 12/2026");

      assertThat(extract("13_fabrikneues-fahrzeug-anmelden.md", properties).documentTypeCode())
          .isEmpty();
    }

    @Test
    void aQuotationBelowTheTitleLineNeverNamesTheDokumentart() {
      // 15_faq-ausweisbeantragung.pdf: a FAQ that cites a Dienstanweisung is none.
      DocumentProperties properties =
          DocumentProperties.EMPTY.withTitleLine(
              "Häufige Fragen zur Ausweisbeantragung\nTermine werden nach der Dienstanweisung"
                  + " zur Terminvergabe vergeben.");

      assertThat(properties.titleLine())
          .as("DocumentProperties keeps the first line and nothing else")
          .isEqualTo("Häufige Fragen zur Ausweisbeantragung");
      assertThat(extract("15_faq-ausweisbeantragung.pdf", properties).documentTypeCode()).isEmpty();
    }

    @Test
    void aTitleLineNamingTheDokumentartStillCounts() {
      DocumentProperties properties =
          DocumentProperties.EMPTY.withTitleLine(
              "Dienstanweisung Nr. 3 – Terminvergabe\nGilt ab dem 01.04.2026.");

      assertThat(extract("03_terminvergabe.pdf", properties).documentTypeCode())
          .contains("DIENSTANWEISUNG");
    }

    @Test
    void aSectionHeadingFromInsideTheDocumentNeverNamesTheDokumentart() {
      // A Markdown Leistungsbeschreibung opening with a level-2 title and carrying a level-1
      // section "Benötigtes Formular" further down: the section names the Formular the service
      // needs, exactly the reference the label line was.
      DocumentProperties properties =
          DocumentProperties.EMPTY
              .withTitleLine("Fabrikneues Fahrzeug anmelden")
              .withFirstHeading("Benötigtes Formular");

      assertThat(extract("13_fabrikneues-fahrzeug-anmelden.md", properties).documentTypeCode())
          .isEmpty();
    }

    @Test
    void aPdfOutlineEntryNeverOutranksTheFirstTextLine() {
      // firstHeading of a PDF is an outline entry from anywhere in the document, not its first
      // line - two different codes are an ambiguity, and the field stays empty.
      DocumentProperties properties =
          DocumentProperties.EMPTY
              .withFirstHeading("Benötigtes Formular")
              .withTitleLine("Protokoll der Sitzung des Rates");

      assertThat(extract("anlage.pdf", properties).documentTypeCode()).isEmpty();
    }

    @Test
    void aFirstHeadingWithoutADokumentartLeavesTheTitleLineItsOwn() {
      DocumentProperties properties =
          DocumentProperties.EMPTY
              .withFirstHeading("Anlagen und Fristen")
              .withTitleLine("Protokoll der Sitzung des Rates");

      assertThat(extract("anlage.pdf", properties).documentTypeCode()).contains("PROTOKOLL");
    }
  }

  /** the file format as the last source. */
  @Nested
  class SyntheticName {

    private final DocumentProperties headline = DocumentProperties.EMPTY.withSyntheticName(true);

    @Test
    void aHeadlineIsNoNamingConventionForTheDokumentart() {
      // An RSS entry's name is its headline: it names what the article is about, not what the
      // article is. Both the exact token and the Kompositum ending are off here.
      assertThat(extract("Rat beschließt neue Hundesteuersatzung", headline).documentTypeCode())
          .isEmpty();
      assertThat(extract("Vermerk zur Sitzung veröffentlicht", headline).documentTypeCode())
          .isEmpty();
    }

    @Test
    void aHeadlineIsNoNamingConventionForTheDate() {
      assertThat(extract("Haushalt 2024 beschlossen", headline).date()).isEmpty();
      assertThat(extract("Bürgerbüro ab 12.03.2026 länger geöffnet", headline).date()).isEmpty();
      // A date the feed itself declares still counts - it is the entry's own date, not a name.
      assertThat(
              extract(
                      "Haushalt 2024 beschlossen",
                      headline.withDocumentDate(LocalDate.of(2026, 3, 12)))
                  .date())
          .contains(ExtractedDate.day(LocalDate.of(2026, 3, 12)));
    }

    @Test
    void aHeadlineIsStillATitle() {
      assertThat(extract("Rat beschließt neue Hundesteuersatzung", headline).title())
          .hasValueSatisfying(title -> assertThat(title).contains("Hundesteuersatzung"));
    }

    @Test
    void aRealFileNameOfTheSameWordingStillCarriesItsConvention() {
      assertThat(
              extract("Rat beschließt neue Hundesteuersatzung.pdf", DocumentProperties.EMPTY)
                  .documentTypeCode())
          .contains("SATZUNG_ORDNUNG");
      assertThat(extract("Haushalt 2024 beschlossen.pdf", DocumentProperties.EMPTY).date())
          .contains(ExtractedDate.year(2024));
    }
  }

  /** the file format as the last source. */
  @Nested
  class FileFormatSource {

    @Test
    void aPresentationFormatYieldsPraesentationWhenNoTextSourceDoes() {
      assertThat(
              extract(
                      "21_onboarding-buergerbuero.pptx",
                      DocumentProperties.EMPTY.withFormatExtension(".pptx"))
                  .documentTypeCode())
          .contains("PRAESENTATION");
      assertThat(
              extract("anlage.odp", DocumentProperties.EMPTY.withFormatExtension(".odp"))
                  .documentTypeCode())
          .contains("PRAESENTATION");
      // Only the two formats SupportedDocumentFormats admits as presentations. The file name stays
      // neutral here: "folien" is a seeded synonym and would resolve without any format rule.
      assertThat(
              extract("anlage.ppt", DocumentProperties.EMPTY.withFormatExtension(".ppt"))
                  .documentTypeCode())
          .isEmpty();
    }

    @Test
    void everyTextSourceOutranksTheFormat() {
      DocumentProperties properties =
          DocumentProperties.EMPTY.withFormatExtension(".pptx").withFirstHeading("Vermerk");

      assertThat(extract("anlage.pptx", properties).documentTypeCode()).contains("VERMERK");
      assertThat(
              extract(
                      "Protokoll_Sitzung.pptx",
                      DocumentProperties.EMPTY.withFormatExtension(".pptx"))
                  .documentTypeCode())
          .contains("PROTOKOLL");
    }

    @Test
    void aFormatThatCarriesEveryDokumentartYieldsNone() {
      assertThat(
              extract("anlage.pdf", DocumentProperties.EMPTY.withFormatExtension(".pdf"))
                  .documentTypeCode())
          .isEmpty();
      assertThat(
              extract("anlage.docx", DocumentProperties.EMPTY.withFormatExtension(".docx"))
                  .documentTypeCode())
          .isEmpty();
    }

    @Test
    void aVocabularyWithoutThePresentationCodeYieldsNothingForTheFormat() {
      ExtractedCoreMetadata result =
          CoreMetadataExtractor.extract(
              "folien.pptx",
              DocumentProperties.EMPTY.withFormatExtension(".pptx"),
              DocumentTypeVocabulary.empty());

      assertThat(result.documentTypeCode()).isEmpty();
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

    // A bare four-digit number in free heading text is an amount, a paragraph number or
    // a threshold - never a Stand. Only an anchored year ("Stand 2026", "Fassung 2024") counts.
    @Test
    void aBareNumberInTheHeadingIsNeverADate() {
      for (String heading :
          List.of(
              "Gebührensatzung — Beträge bis 2000 Euro",
              "Anlage 3 zu § 2000",
              "Richtwert 1990 kWh",
              "Zuwendungen ab 2019 Euro")) {
        assertThat(
                extract("anlage.docx", DocumentProperties.EMPTY.withFirstHeading(heading)).date())
            .as(heading)
            .isEmpty();
      }
    }

    @Test
    void anAnchoredYearInTheHeadingCountsAsAStand() {
      assertThat(
              extract(
                      "satzung.docx",
                      DocumentProperties.EMPTY.withFirstHeading("Gebührensatzung, Fassung 2024"))
                  .date())
          .contains(ExtractedDate.year(2024));
      assertThat(
              extract(
                      "satzung.docx",
                      DocumentProperties.EMPTY.withFirstHeading("Dienstanweisung Stand: 2026"))
                  .date())
          .contains(ExtractedDate.year(2026));
    }

    @Test
    void aBareYearStillCountsFromTheFileNameAndTheFrontmatter() {
      assertThat(extract("Haushaltsplan_2024.pdf", DocumentProperties.EMPTY).date())
          .contains(ExtractedDate.year(2024));
      assertThat(
              extract(
                      "satzung.md",
                      DocumentProperties.EMPTY.withFrontmatter(Map.of("fassung", "2023")))
                  .date())
          .contains(ExtractedDate.year(2023));
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
