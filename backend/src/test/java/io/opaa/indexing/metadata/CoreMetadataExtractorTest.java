package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DatePrecision;
import io.opaa.indexing.format.DocumentProperties;
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

  /**
   * the title findings of the Handstichprobe from 05.09.2026
   * (eval/reports/metadata-extraction-sample-2026-09-05.md, 14 of 100 documents wrong): a tool's
   * own title was taken over unchecked, and the file-name fallback ran before the document's own
   * heading and title line.
   */
  @Nested
  class ToolAndFileNameTitles {

    // smbprn.00009008.KdcPjl.pdf of the sample: the PDF Title is the printer driver's print style,
    // the document's own name is the first line of the printout.
    @Test
    void aPrinterDriverTitleIsDiscardedInFavourOfTheTitleLine() {
      DocumentProperties properties =
          DocumentProperties.EMPTY
              .withTitle("Microsoft Office Outlook - Memo Style")
              .withTitleLine("Test Attachment");

      assertThat(extract("smbprn.00009008.KdcPjl.pdf", properties).title())
          .contains("Test Attachment");
    }

    @Test
    void aConversionTitleNamingTheSourceFileIsDiscarded() {
      for (String toolTitle :
          List.of(
              "Microsoft Word - vermerk-terminvergabe.doc",
              "Microsoft PowerPoint - onboarding.pptx",
              "LibreOffice Writer - satzung.odt",
              "vermerk-terminvergabe.docx")) {
        assertThat(
                extract(
                        "vermerk-terminvergabe.pdf",
                        DocumentProperties.EMPTY
                            .withTitle(toolTitle)
                            .withFirstHeading("Vermerk zur Terminvergabe"))
                    .title())
            .as(toolTitle)
            .contains("Vermerk zur Terminvergabe");
      }
    }

    // A title equal to the file name is the better spelling of it - the humanized fallback caps at
    // eight tokens and drops the rest of a long Satzungstitel.
    @Test
    void aTitlePropertyRepeatingTheFileNameIsKept() {
      String title = "Satzung über die Erhebung von Gebühren für Amtshandlungen des Standesamts";
      DocumentProperties properties = DocumentProperties.EMPTY.withTitle(title);

      assertThat(
              extract(
                      "Satzung über die Erhebung von Gebühren für Amtshandlungen des"
                          + " Standesamts.pdf",
                      properties)
                  .title())
          .contains(title);
    }

    // A mail's subject is the document's own title and regularly names an attached file; the
    // pipeline sets it without marking a synthetic name.
    @Test
    void aMailSubjectNamingAFileIsNoFileNameTitle() {
      for (String subject :
          List.of(
              "WG: haushaltsplan-2026.pdf",
              "Anbei: Vermerk.docx",
              "Weiterleitung: Antrag Wohngeld.pdf")) {
        assertThat(extract("nachricht.eml", DocumentProperties.EMPTY.withTitle(subject)).title())
            .as(subject)
            .contains(subject);
      }
    }

    @Test
    void anOrdinaryTitlePropertyStillWins() {
      for (String title :
          List.of(
              "Verwaltungsgebührensatzung der Stadt Rheinfurt",
              "Interne FAQ: Häufige Rückfragen zur Ummeldung",
              "Microsoft-Lizenzen im Rathaus",
              // A tool name is only a tool title with a file or a print style behind it.
              "Microsoft 365 - Leitfaden für Beschäftigte",
              "Adobe Acrobat - Schulungsunterlagen der IT")) {
        assertThat(
                extract(
                        "01_verwaltungsgebuehrensatzung.pdf",
                        DocumentProperties.EMPTY.withTitle(title))
                    .title())
            .as(title)
            .contains(title);
      }
    }

    // The 12 .txt Leistungsbeschreibungen of the sample: the Setext heading reaches the extractor
    // as the first heading and now outranks the humanized file name.
    @Test
    void aHeadingOutranksTheHumanizedFileName() {
      DocumentProperties properties =
          DocumentProperties.EMPTY
              .withFirstHeading("Aus dem Ausland eingeführtes Fahrzeug anmelden")
              .withTitleLine("Aus dem Ausland eingeführtes Fahrzeug anmelden");

      assertThat(
              extract("002_aus-dem-ausland-eingefuehrtes-fahrzeug-anmelden.txt", properties)
                  .title())
          .contains("Aus dem Ausland eingeführtes Fahrzeug anmelden");
    }

    @Test
    void aTitleLineThatIsRunningTextLeavesTheFileNameTheTitle() {
      // foerderbescheid-anlage-zwei.txt of the sample: one sentence, no heading. Its file name is
      // the better title, and the sample counts it as correct.
      DocumentProperties properties =
          DocumentProperties.EMPTY.withTitleLine(
              "Anlage zwei: Berechnungsgrundlage der Foerdersumme nach Richtlinie 7.");

      assertThat(extract("foerderbescheid-anlage-zwei.txt", properties).title())
          .contains("foerderbescheid anlage zwei");
    }

    @Test
    void aLabelLineIsNoTitleEither() {
      DocumentProperties properties = DocumentProperties.EMPTY.withTitleLine("Zustaendige Stelle:");

      assertThat(extract("030_umtausch-in-kartenfuehrerschein.txt", properties).title())
          .contains("umtausch in kartenfuehrerschein");
    }

    // demo/corpus/ratsinformationen/**: a Beschlussvorlage opens with the letterhead of the city
    // and a block of labelled fields - neither is the subject of the document.
    @Test
    void aLetterheadAboveALabelBlockIsNoTitle() {
      String head =
          """
          STADT RHEINFURT
          Beschlussvorlage Nr. 2024/019

          Gremium:        Hauptausschuss der Stadt Rheinfurt
          Sitzung am:     14. Mai 2024
          Federführung:   Bürgerbüro Rheinfurt
          Status:         öffentlich

          Betreff: Anschaffung eines Bürgerkoffers für die mobile Beratung
          """;
      DocumentProperties properties =
          DocumentProperties.EMPTY.withTitleLine(head).withHeadText(head);

      assertThat(properties.titleLine()).isEqualTo("STADT RHEINFURT");
      assertThat(extract("2024-05-14-hauptausschuss-vorlage-buergerkoffer.txt", properties).title())
          .contains("hauptausschuss vorlage buergerkoffer");
    }

    @Test
    void aHeadingAboveALabelBlockIsNoTitleEither() {
      String head =
          """
          Beschlussvorlage Nr. 2024/019

          Gremium:        Hauptausschuss
          Status:         öffentlich
          """;

      assertThat(
              extract(
                      "2024-05-14-vorlage.txt",
                      DocumentProperties.EMPTY.withTitleLine(head).withHeadText(head))
                  .title())
          .contains("vorlage");
    }

    @Test
    void anOrdinaryHeadingAboveRunningTextStaysTheTitle() {
      String head =
          """
          Wunschkennzeichen reservieren

          Sie können sich ein Wunschkennzeichen vorab reservieren lassen.
          """;

      assertThat(
              extract(
                      "008_wunschkennzeichen.txt",
                      DocumentProperties.EMPTY.withTitleLine(head).withHeadText(head))
                  .title())
          .contains("Wunschkennzeichen reservieren");
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

  /**
   * the date findings of the Handstichprobe from 05.09.2026
   * (eval/reports/metadata-extraction-sample-2026-09-05.md, 27 of 100 documents wrong): every one
   * of them was a generator's template date out of the file properties. Grouped by generator, as
   * the report's own table is - the 27 documents differ only in the date their generator stamps.
   */
  @Nested
  class GeneratorDefaultsInFileProperties {

    @Test
    void aGeneratorTemplateDateIsNoDate() {
      // python-docx (10 documents), python-pptx (3) and ReportLab in its reproducible mode (14).
      for (LocalDate templateDate :
          List.of(
              LocalDate.of(2013, 12, 23),
              LocalDate.of(2013, 1, 27),
              LocalDate.of(2000, 1, 1),
              LocalDate.of(1980, 1, 1),
              LocalDate.of(1970, 1, 1),
              LocalDate.of(1601, 1, 1))) {
        DocumentProperties properties =
            DocumentProperties.EMPTY.withCreatedAt(templateDate).withModifiedAt(templateDate);

        assertThat(extract("01_identitaetszweifel-ausweisantrag.docx", properties).date())
            .as(templateDate.toString())
            .isEmpty();
      }
    }

    @Test
    void aFilePropertyDateBeforeTheMinimumYearIsNoDate() {
      DocumentProperties properties =
          DocumentProperties.EMPTY.withModifiedAt(LocalDate.of(1989, 6, 4));

      assertThat(extract("vermerk.docx", properties).date()).isEmpty();
    }

    @Test
    void anOrdinaryFilePropertyDateIsStillADate() {
      DocumentProperties properties =
          DocumentProperties.EMPTY
              .withModifiedAt(LocalDate.of(2026, 3, 12))
              .withCreatedAt(LocalDate.of(2025, 1, 2));

      assertThat(extract("vermerk.docx", properties).date())
          .contains(ExtractedDate.day(LocalDate.of(2026, 3, 12)));
    }

    @Test
    void anImplausibleModifiedDateDoesNotStopThePlausibleCreatedDate() {
      DocumentProperties properties =
          DocumentProperties.EMPTY
              .withModifiedAt(LocalDate.of(2000, 1, 1))
              .withCreatedAt(LocalDate.of(2026, 3, 12));

      assertThat(extract("vermerk.docx", properties).date())
          .contains(ExtractedDate.day(LocalDate.of(2026, 3, 12)));
    }

    // A date the format declares as the document's own (a mail's Date header, a feed entry's
    // publication date) is no template date: a mail genuinely sent on 2000-01-01 counts.
    @Test
    void theDocumentsOwnDateIsOnlyCheckedAgainstTheMinimumYear() {
      assertThat(
              extract(
                      "nachricht.eml",
                      DocumentProperties.EMPTY.withDocumentDate(LocalDate.of(2000, 1, 1)))
                  .date())
          .contains(ExtractedDate.day(LocalDate.of(2000, 1, 1)));
      assertThat(
              extract(
                      "nachricht.eml",
                      DocumentProperties.EMPTY.withDocumentDate(LocalDate.of(1601, 1, 1)))
                  .date())
          .isEmpty();
    }
  }

  /**
   * the anchored date statements of the head text - the source that rescues the eleven Satzungen of
   * the sample, whose Inkrafttretensklausel stood in the document while the file properties carried
   * ReportLab's 2000-01-01.
   */
  @Nested
  class HeadTextDates {

    private DocumentProperties satzung(String text) {
      return DocumentProperties.EMPTY
          .withCreatedAt(LocalDate.of(2000, 1, 1))
          .withModifiedAt(LocalDate.of(2000, 1, 1))
          .withHeadText(text);
    }

    @Test
    void theInkrafttretensklauselBeatsTheGeneratorDefault() {
      // 01_verwaltungsgebuehrensatzung.pdf of the sample, abridged: the clause sits in the closing
      // provisions, roughly 2.000 characters into the document.
      String satzung =
          """
          Verwaltungsgebührensatzung der Stadt Rheinfurt
          Aktenzeichen (Muster): AZ 20.1-2026-0001
          § 1 Geltungsbereich
          Diese Satzung regelt die Erhebung von Verwaltungsgebühren.
          """
              + "Gebührenpflichtig ist, wer die Amtshandlung veranlasst hat. ".repeat(30)
              + """
              § 6 Inkrafttreten
              Diese Satzung tritt am 1. Januar 2026 in Kraft.
              Anlage: Gebührenverzeichnis
              """;

      assertThat(extract("01_verwaltungsgebuehrensatzung.pdf", satzung(satzung)).date())
          .contains(ExtractedDate.day(LocalDate.of(2026, 1, 1)));
    }

    @Test
    void everySelfDesignatingWordingIsRead() {
      for (String clause :
          List.of(
              "Diese Satzung tritt am 1. Januar 2026 in Kraft.",
              "Diese Verordnung tritt am 01.01.2026 in Kraft.",
              "Diese Dienstanweisung tritt am 1. Januar 2026 in Kraft und ersetzt die Regelung"
                  + " vom 3. Mai 2019.",
              "Diese Ordnung tritt rückwirkend zum 1. Januar 2026 in Kraft.")) {
        assertThat(extract("satzung.pdf", satzung(clause)).date())
            .as(clause)
            .contains(ExtractedDate.day(LocalDate.of(2026, 1, 1)));
      }
    }

    @Test
    void aStandStatementInTheHeadBlockIsRead() {
      assertThat(
              extract(
                      "beispiel-word.docx",
                      DocumentProperties.EMPTY
                          .withCreatedAt(LocalDate.of(2013, 12, 23))
                          .withHeadText(
                              "Muster eines Verwaltungsdokuments\n"
                                  + "Dateiformat dieses Musters: Word (.docx) · Stand:"
                                  + " 2. März 2026\n"))
                  .date())
          .contains(ExtractedDate.day(LocalDate.of(2026, 3, 2)));
      assertThat(
              extract(
                      "satzung.pdf",
                      DocumentProperties.EMPTY.withHeadText(
                          "Gebührensatzung\nFassung vom" + " 12.03.2026\n"))
                  .date())
          .contains(ExtractedDate.day(LocalDate.of(2026, 3, 12)));
      assertThat(
              extract("satzung.pdf", DocumentProperties.EMPTY.withHeadText("Satzung\nStand: 2024"))
                  .date())
          .contains(ExtractedDate.year(2024));
    }

    @Test
    void aStandStatementBelowTheHeadBlockIsAnotherDocumentsVersion() {
      String text =
          "Merkblatt zur Kfz-Zulassung\n"
              + "Bitte bringen Sie alle Unterlagen vollständig mit. ".repeat(20)
              + "\nStand: 12.03.2020 der beigefügten Gebührenordnung\n";

      assertThat(extract("merkblatt.pdf", DocumentProperties.EMPTY.withHeadText(text)).date())
          .isEmpty();
    }

    /** the Gegenbeispiele: no date out of running text beyond an anchored statement. */
    @Test
    void noDateIsReadOutOfRunningText() {
      for (String text :
          List.of(
              // 035_verlaengerung-befristeter-fuehrerschein-klassen.md of the demo corpus: a
              // reference to a law, not a statement about this document.
              "Aufgrund von Änderungen des Berufskraftfahrerqualifikationsgesetzes, die zum"
                  + " 23.5.2021 in Kraft getreten sind, wird die Schlüsselzahl 95 nicht mehr"
                  + " eingetragen.",
              "Das neue Gesetz tritt am 1. Januar 2024 in Kraft.",
              "Führerscheine, die bis 31.12.2020 ausgestellt wurden, sind umzutauschen.",
              "Die Gebühr beträgt 2026 Euro.",
              "Wir verweisen auf die Satzung vom 12.03.2020.",
              "Anlage 3 zu § 2000")) {
        assertThat(extract("merkblatt.md", DocumentProperties.EMPTY.withHeadText(text)).date())
            .as(text)
            .isEmpty();
      }
    }

    // Inside the anchor's window a bare year counts only right behind the anchor; further along it
    // belongs to a phrase.
    @Test
    void aBareYearCountsOnlyImmediatelyBehindTheAnchor() {
      assertThat(
              extract(
                      "merkblatt.pdf",
                      DocumentProperties.EMPTY.withHeadText(
                          "Merkblatt\nStand der Technik 2019 in der Kfz-Zulassung"))
                  .date())
          .isEmpty();
      assertThat(
              extract(
                      "merkblatt.pdf",
                      DocumentProperties.EMPTY.withHeadText("Merkblatt\nStand 2019"))
                  .date())
          .contains(ExtractedDate.year(2019));
    }

    // A headline an upstream source declared names other documents than itself - its text is no
    // self-designation, just as its name is no naming convention.
    @Test
    void aSyntheticNameNeverReadsADateOutOfTheHeadText() {
      DocumentProperties properties =
          DocumentProperties.EMPTY
              .withSyntheticName(true)
              .withHeadText("Diese Satzung tritt am 1. Januar 2026 in Kraft.");

      assertThat(extract("Rat beschließt neue Hundesteuersatzung", properties).date()).isEmpty();
    }

    @Test
    void anAnchoredHeadTextDateOutranksTheFileNameAndTheFileProperties() {
      DocumentProperties properties =
          DocumentProperties.EMPTY
              .withHeadText("Gebührensatzung\nStand: 12.03.2026")
              .withModifiedAt(LocalDate.of(2025, 1, 1));

      assertThat(extract("2019-01-01_satzung.pdf", properties).date())
          .contains(ExtractedDate.day(LocalDate.of(2026, 3, 12)));
    }

    @Test
    void theHeadingStillOutranksTheHeadText() {
      DocumentProperties properties =
          DocumentProperties.EMPTY
              .withFirstHeading("Gebührensatzung, Fassung 2024")
              .withHeadText("Gebührensatzung\nStand: 12.03.2026");

      assertThat(extract("satzung.pdf", properties).date()).contains(ExtractedDate.year(2024));
    }
  }

  @Test
  void aGermanLongDateCarriesDayPrecision() {
    assertThat(
            extract(
                    "satzung.pdf",
                    DocumentProperties.EMPTY.withFirstHeading(
                        "Gebührensatzung, Stand 1. Januar" + " 2026"))
                .date())
        .contains(ExtractedDate.day(LocalDate.of(2026, 1, 1)));
  }

  @Test
  void anEmptyVocabularyNeverYieldsADocumentType() {
    ExtractedCoreMetadata result =
        CoreMetadataExtractor.extract(
            "Dienstanweisung.pdf", DocumentProperties.EMPTY, DocumentTypeVocabulary.empty());

    assertThat(result.documentTypeCode()).isEmpty();
  }
}
