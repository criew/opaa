package io.opaa.format.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.opaa.format.chunk.ChunkMetadataKeys;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;

/**
 * A section whose body text has no inner heading to cut at is split into several chunks instead of
 * being truncated: nothing of its text is lost, every part keeps the section's heading line and
 * Fundort, and every part fits the token budget the embedding step enforces.
 */
class HeadingSectionSplitterOversizedSectionTest {

  private static final String HEADING = "II. Aufbewahrungsfristen";

  @Test
  void aSectionFarBeyondTheHardLimitIsSplitAndItsLastSentenceIsStillIndexed() {
    // regression guard for #2327: the text past 20,000 characters ended in "[…gekürzt]" and was
    // in no chunk at all.
    String body = fristenListe(19, 1_400);
    assertThat(body.length()).isGreaterThan(25_000);

    List<Document> chunks = chunk(body);

    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(joinedText(chunks)).contains(lastSentenceOf(19)).doesNotContain("[…gekürzt]");
  }

  @Test
  void everyPartKeepsTheHeadingLineAndTheSectionsFundort() {
    List<Document> chunks = chunk(fristenListe(19, 1_400));

    assertThat(chunks)
        .allSatisfy(
            chunk -> {
              assertThat(chunk.getText()).startsWith(HEADING + "\n\n");
              assertThat(chunk.getMetadata().get(ChunkMetadataKeys.LOCATION_METADATA_KEY))
                  .isEqualTo("Abschn. " + HEADING);
            });
  }

  @Test
  void theSplitLosesNoTextAndEveryPartStaysWithinTheSoftBudget() {
    String body = fristenListe(19, 1_400);

    List<Document> chunks = chunk(body);

    assertThat(normalized(bodiesOf(chunks))).isEqualTo(normalized(body));
    assertThat(chunks)
        .allSatisfy(
            chunk ->
                assertThat(bodyOf(chunk).length())
                    .isLessThanOrEqualTo(HeadingSectionSplitter.SOFT_CHUNK_CHAR_LIMIT));
  }

  @Test
  void aBlockOfLinesIsCutBetweenLinesNotInsideOne() {
    String body = fristenListe(19, 1_400);
    List<String> lines = body.lines().toList();

    List<Document> chunks = chunk(body);

    assertThat(chunks)
        .allSatisfy(chunk -> assertThat(lines).containsAll(bodyOf(chunk).lines().toList()));
  }

  @Test
  void aBlockWithoutLineBreaksIsCutAtSentenceEnds() {
    StringBuilder body = new StringBuilder();
    for (int i = 1; body.length() < 12_000; i++) {
      body.append("Der Antrag Nummer ")
          .append(i)
          .append(" wird innerhalb von drei Monaten nach Eingang beschieden. ");
    }

    List<Document> chunks = chunk(body.toString().strip());

    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks.subList(0, chunks.size() - 1))
        .allSatisfy(chunk -> assertThat(bodyOf(chunk)).endsWith("beschieden."));
  }

  @Test
  void aRunWithoutAnyBoundaryIsCutHardWithoutLosingACharacter() {
    String body = "x".repeat(25_000);

    List<Document> chunks = chunk(body);

    assertThat(bodiesOf(chunks).replace("\n", "")).isEqualTo(body);
  }

  @Test
  void everyPartFitsTheTokenBudgetOfTheEmbeddingStep() {
    // regression guard for #2328: one oversized chunk made the default TokenCountBatchingStrategy
    // (the one PgVectorStore's auto-configuration wires) reject the whole document. The list
    // below is deliberately token-dense - numbers and file references - as in real Fristenlisten.
    String body = fristenListe(30, 1_200);
    assertThat(body.length()).isGreaterThan(30_000);
    List<Document> chunks = chunk(body);

    assertThatCode(() -> new TokenCountBatchingStrategy().batch(chunks)).doesNotThrowAnyException();
  }

  private static List<Document> chunk(String body) {
    return HeadingSectionSplitter.chunk(
        List.of(
            new HeadingSectionSplitter.Heading(1, HEADING),
            new HeadingSectionSplitter.Paragraph(body)),
        3);
  }

  /**
   * One Fristenliste as a single block, one line per numbered entry - the shape a PDF section
   * without inner headings arrives in. Each entry is about {@code entryLength} characters long.
   */
  static String fristenListe(int entries, int entryLength) {
    List<String> lines = new ArrayList<>();
    for (int n = 1; n <= entries; n++) {
      StringBuilder line = new StringBuilder("3.2." + n + " Unterlagen der Gruppe " + n + ":");
      for (int k = 1; line.length() < entryLength; k++) {
        line.append(
            String.format(
                " Az. %04d/%d-%02d vom %02d.%02d.%d, Frist %d Jahre nach Ablauf des %02d.%02d.%d"
                    + " (Kst. %05d).",
                (n * 37 + k * 11) % 10_000,
                2000 + (n + k) % 26,
                k % 100,
                1 + k % 28,
                1 + n % 12,
                1990 + k % 35,
                1 + (n + k) % 30,
                1 + (n * k) % 28,
                1 + k % 12,
                2000 + n % 26,
                (n * 1_000 + k * 7) % 100_000));
      }
      line.append(" ").append(lastSentenceOf(n));
      lines.add(line.toString());
    }
    return String.join("\n", lines);
  }

  static String lastSentenceOf(int entry) {
    return "Ende des Eintrags Nummer " + entry + ".";
  }

  private static String bodyOf(Document chunk) {
    return chunk.getText().substring((HEADING + "\n\n").length());
  }

  private static String bodiesOf(List<Document> chunks) {
    return String.join("\n", chunks.stream().map(c -> bodyOf(c)).toList());
  }

  private static String joinedText(List<Document> chunks) {
    return String.join("\n", chunks.stream().map(Document::getText).toList());
  }

  private static String normalized(String text) {
    return text.replaceAll("\\s+", " ").strip();
  }
}
