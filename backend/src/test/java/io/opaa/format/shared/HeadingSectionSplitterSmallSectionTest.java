package io.opaa.format.shared;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.format.chunk.ChunkMetadataKeys;
import io.opaa.format.shared.HeadingSectionSplitter.Event;
import io.opaa.format.shared.HeadingSectionSplitter.Heading;
import io.opaa.format.shared.HeadingSectionSplitter.Paragraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * Neighbouring tiny sections under the same parent heading are combined into chunks of up to the
 * target size, without crossing the boundary of a higher heading level. The documents are a
 * synthetic Haushaltsplan: "Kapitel › Ausgaben › Titelgruppe › Titel", one line per Titel.
 */
class HeadingSectionSplitterSmallSectionTest {

  private static final String KAPITEL = "Kapitel 0801 Finanzämter";
  private static final String AUSGABEN = "Ausgaben";

  @Test
  void tinyTitlesUnderTheSameTitelgruppeAreCombinedInsteadOfOneChunkEach() {
    // regression guard for #2331: every Titel became a chunk of its own, most below 200 chars.
    List<Event> events = haushaltsplan(List.of("51", "52"), 12);

    List<Document> chunks = HeadingSectionSplitter.chunk(events, Integer.MAX_VALUE);

    assertThat(chunks).hasSize(2);
    assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getText().length()).isGreaterThan(500));
  }

  @Test
  void noTitelIsLostAndEachAppearsInExactlyOneChunkUnderItsOwnHeading() {
    List<Document> chunks =
        HeadingSectionSplitter.chunk(haushaltsplan(List.of("51", "52"), 12), Integer.MAX_VALUE);

    for (String gruppe : List.of("51", "52")) {
      for (int n = 1; n <= 12; n++) {
        String titel = titel(gruppe, n);
        String line = titelLine(gruppe, n);
        assertThat(chunks.stream().filter(c -> c.getText().contains(line)).count())
            .as(titel)
            .isEqualTo(1);
        assertThat(chunks).anySatisfy(c -> assertThat(c.getText()).contains(titel + "\n\n" + line));
      }
    }
  }

  @Test
  void aCombinedChunkCarriesTheCommonHeadingPathAsHeadingLineAndFundort() {
    List<Document> chunks =
        HeadingSectionSplitter.chunk(haushaltsplan(List.of("51"), 12), Integer.MAX_VALUE);

    String common = KAPITEL + " › " + AUSGABEN + " › " + titelgruppe("51");
    assertThat(chunks).hasSize(1);
    assertThat(chunks.getFirst().getText()).startsWith(common + "\n\n");
    assertThat(chunks.getFirst().getMetadata().get(ChunkMetadataKeys.LOCATION_METADATA_KEY))
        .isEqualTo("Abschn. " + common);
  }

  @Test
  void titlesOfDifferentTitelgruppenNeverShareAChunk() {
    List<Document> chunks =
        HeadingSectionSplitter.chunk(
            haushaltsplan(List.of("51", "52", "53"), 3), Integer.MAX_VALUE);

    assertThat(chunks)
        .allSatisfy(
            chunk -> {
              long gruppen =
                  List.of("51", "52", "53").stream()
                      .filter(g -> chunk.getText().contains(titelLine(g, 1)))
                      .count();
              assertThat(gruppen).isLessThanOrEqualTo(1);
            });
  }

  @Test
  void combiningStopsAtTheTargetSize() {
    List<Document> chunks =
        HeadingSectionSplitter.chunk(haushaltsplan(List.of("51"), 120), Integer.MAX_VALUE);

    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks)
        .allSatisfy(
            chunk ->
                assertThat(chunk.getText().length())
                    .isLessThanOrEqualTo(HeadingSectionSplitter.SOFT_CHUNK_CHAR_LIMIT));
    assertThat(chunks.stream().filter(c -> c.getText().contains(titelLine("51", 120))).count())
        .isEqualTo(1);
  }

  @Test
  void sectionsOfRegularSizeStayChunksOfTheirOwn() {
    List<Event> events = new ArrayList<>();
    events.add(new Heading(1, "Satzung"));
    events.add(new Heading(2, "§ 1 Geltungsbereich"));
    events.add(new Paragraph(absatz("Geltungsbereich", 900)));
    events.add(new Heading(2, "§ 2 Begriffsbestimmungen"));
    events.add(new Paragraph(absatz("Begriffsbestimmungen", 900)));

    List<Document> chunks = HeadingSectionSplitter.chunk(events, 3);

    assertThat(chunks).hasSize(2);
    assertThat(chunks.get(0).getText()).startsWith("Satzung › § 1 Geltungsbereich\n\n");
    assertThat(chunks.get(1).getText()).startsWith("Satzung › § 2 Begriffsbestimmungen\n\n");
  }

  @Test
  void topLevelSectionsWithoutACommonHeadingAreNotCombined() {
    List<Event> events =
        List.of(
            new Heading(1, "Anlage 1"),
            new Paragraph("Muster des Antragsformulars."),
            new Heading(1, "Anlage 2"),
            new Paragraph("Muster des Bescheids."));

    List<Document> chunks = HeadingSectionSplitter.chunk(events, 3);

    assertThat(chunks)
        .extracting(Document::getText)
        .containsExactly(
            "Anlage 1\n\nMuster des Antragsformulars.", "Anlage 2\n\nMuster des Bescheids.");
  }

  @Test
  void aTinySectionIsNotCombinedAcrossTheBoundaryOfItsParent() {
    List<Event> events = new ArrayList<>();
    events.add(new Heading(1, KAPITEL));
    events.add(new Heading(2, "Einnahmen"));
    events.add(new Heading(3, "Titel 119 01"));
    events.add(new Paragraph("Vermischte Einnahmen 12.000"));
    events.add(new Heading(2, AUSGABEN));
    events.add(new Heading(3, "Titel 511 01"));
    events.add(new Paragraph("Geschäftsbedarf 80.000"));

    List<Document> chunks = HeadingSectionSplitter.chunk(events, 3);

    assertThat(chunks)
        .extracting(Document::getText)
        .containsExactly(
            KAPITEL + " › Einnahmen › Titel 119 01\n\nVermischte Einnahmen 12.000",
            KAPITEL + " › " + AUSGABEN + " › Titel 511 01\n\nGeschäftsbedarf 80.000");
  }

  @Test
  void anIntroductionOfTheParentIsCombinedWithItsTinyChildren() {
    List<Event> events = new ArrayList<>();
    events.add(new Heading(1, KAPITEL));
    events.add(new Heading(2, AUSGABEN));
    events.add(new Paragraph("Erläuterungen zu den Ausgaben des Kapitels."));
    events.add(new Heading(3, "Titel 511 01"));
    events.add(new Paragraph("Geschäftsbedarf 80.000"));
    events.add(new Heading(3, "Titel 511 02"));
    events.add(new Paragraph("Bücher und Zeitschriften 4.000"));

    List<Document> chunks = HeadingSectionSplitter.chunk(events, 3);

    assertThat(chunks).hasSize(1);
    assertThat(chunks.getFirst().getText())
        .isEqualTo(
            KAPITEL
                + " › "
                + AUSGABEN
                + "\n\nErläuterungen zu den Ausgaben des Kapitels."
                + "\n\nTitel 511 01\n\nGeschäftsbedarf 80.000"
                + "\n\nTitel 511 02\n\nBücher und Zeitschriften 4.000");
    assertThat(chunks.getFirst().getMetadata().get(ChunkMetadataKeys.LOCATION_METADATA_KEY))
        .isEqualTo("Abschn. " + KAPITEL + " › " + AUSGABEN);
  }

  @Test
  void aSectionSplitForItsSizeDoesNotAbsorbATinyNeighbour() {
    List<Event> events = new ArrayList<>();
    events.add(new Heading(1, KAPITEL));
    events.add(new Heading(2, "Erläuterungen"));
    events.add(new Paragraph(HeadingSectionSplitterOversizedSectionTest.fristenListe(8, 1_400)));
    events.add(new Heading(2, "Hinweis"));
    events.add(new Paragraph("Beträge in Euro."));

    List<Document> chunks = HeadingSectionSplitter.chunk(events, 3);

    assertThat(chunks.getLast().getText()).isEqualTo(KAPITEL + " › Hinweis\n\nBeträge in Euro.");
    assertThat(chunks.subList(0, chunks.size() - 1))
        .allSatisfy(
            chunk -> assertThat(chunk.getText()).startsWith(KAPITEL + " › Erläuterungen\n\n"));
  }

  @Test
  void anIntroductionUnderAusgabenDoesNotPullSeveralTitelgruppenIntoOneChunk() {
    // review finding on #2331: a short intro one level up lifted the group's common heading to
    // "Ausgaben", and the Titel of all Titelgruppen were combined.
    List<Event> events = new ArrayList<>();
    events.add(new Heading(1, KAPITEL));
    events.add(new Heading(2, AUSGABEN));
    events.add(new Paragraph("Erläuterungen siehe Anlage."));
    events.addAll(haushaltsplan(List.of("51", "52"), 2).subList(2, 12));

    List<Document> chunks = HeadingSectionSplitter.chunk(events, Integer.MAX_VALUE);

    assertThat(chunks).allSatisfy(chunk -> assertThat(gruppenIn(chunk)).isLessThanOrEqualTo(1));
  }

  @Test
  void anEmptyTitelgruppeDoesNotPullTheNextTitelgruppesTitelIntoItsChunk() {
    List<Event> events = new ArrayList<>();
    events.add(new Heading(1, KAPITEL));
    events.add(new Heading(2, AUSGABEN));
    events.add(new Heading(3, titelgruppe("50")));
    events.addAll(haushaltsplan(List.of("51"), 3).subList(2, 9));

    List<Document> chunks = HeadingSectionSplitter.chunk(events, Integer.MAX_VALUE);

    assertThat(chunks)
        .filteredOn(chunk -> chunk.getText().contains(titelLine("51", 1)))
        .singleElement()
        .satisfies(
            chunk -> {
              assertThat(chunk.getText()).doesNotContain(titelgruppe("50"));
              assertThat(chunk.getMetadata().get(ChunkMetadataKeys.LOCATION_METADATA_KEY))
                  .isEqualTo("Abschn. " + KAPITEL + " › " + AUSGABEN + " › " + titelgruppe("51"));
            });
  }

  @Test
  void textUnderAnUntitledHeadingIsNotAttributedToThePreviousTitel() {
    // DOCX and ODT emit a Heading with a blank title for an empty heading paragraph.
    List<Event> events = new ArrayList<>();
    events.add(new Heading(1, KAPITEL));
    events.add(new Heading(2, AUSGABEN));
    events.add(new Heading(3, "Titel 511 01"));
    events.add(new Paragraph("Geschäftsbedarf 80.000"));
    events.add(new Heading(3, ""));
    events.add(new Paragraph("Fußnote zur Tabelle."));

    List<Document> chunks = HeadingSectionSplitter.chunk(events, Integer.MAX_VALUE);

    assertThat(chunks)
        .filteredOn(chunk -> chunk.getText().contains("Fußnote zur Tabelle."))
        .singleElement()
        .satisfies(chunk -> assertThat(chunk.getText()).doesNotContain("Titel 511 01"));
  }

  private static long gruppenIn(Document chunk) {
    return List.of("51", "52").stream()
        .filter(g -> chunk.getText().contains(titelLine(g, 1)))
        .count();
  }

  /**
   * One Kapitel with the Ausgaben split into {@code gruppen}, each with {@code titelCount} Titel of
   * a single line - the shape the issue reports from a real Haushaltsplan.
   */
  static List<Event> haushaltsplan(List<String> gruppen, int titelCount) {
    List<Event> events = new ArrayList<>();
    events.add(new Heading(1, KAPITEL));
    events.add(new Heading(2, AUSGABEN));
    for (String gruppe : gruppen) {
      events.add(new Heading(3, titelgruppe(gruppe)));
      for (int n = 1; n <= titelCount; n++) {
        events.add(new Heading(4, titel(gruppe, n)));
        events.add(new Paragraph(titelLine(gruppe, n)));
      }
    }
    return events;
  }

  static String titelgruppe(String gruppe) {
    return "Titelgruppe " + gruppe + " Steuerverwaltung";
  }

  static String titel(String gruppe, int n) {
    return String.format("Titel 5%s %02d", gruppe, n);
  }

  /** The Zweckbestimmung and amount of one Titel, a line of well under 100 characters. */
  static String titelLine(String gruppe, int n) {
    return String.format("Sachverständige Gruppe %s Nr. %d: %d.000 Euro", gruppe, n, n * 37);
  }

  private static String absatz(String topic, int length) {
    StringBuilder text = new StringBuilder();
    for (int i = 1; text.length() < length; i++) {
      text.append("(").append(i).append(") Regelung zum Thema ").append(topic);
      text.append(" mit einer Erläuterung, die einen vollständigen Satz bildet. ");
    }
    return text.toString().strip();
  }
}
