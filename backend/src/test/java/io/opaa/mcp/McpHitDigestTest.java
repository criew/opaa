package io.opaa.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.search.SearchHit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The display layer of {@code search} (#1766): the excerpt around the found place and the summary
 * per document. Both decide what a foreign model pays for out of its context window.
 */
class McpHitDigestTest {

  private static final int EXCERPT = 400;

  private final McpHitDigest digest =
      new McpHitDigest(new McpProperties(List.of("2025-06-18"), 5, EXCERPT));

  @Test
  void aPassageShorterThanTheExcerptIsReturnedWhole() {
    SearchHit hit = hit(UUID.randomUUID(), "Die Widerspruchsfrist beträgt einen Monat.");

    assertThat(digest.condense(List.of(hit), "Widerspruchsfrist", 5).get(0).excerpt())
        .isEqualTo("Die Widerspruchsfrist beträgt einen Monat.");
  }

  @Test
  void theExcerptIsCutAroundTheFoundPlaceAndNotAtTheBeginning() {
    String filler = "Allgemeine Vorbemerkung zum Verfahren. ".repeat(40);
    String text = filler + "Die Widerspruchsfrist beträgt einen Monat. " + filler;

    String excerpt =
        digest.condense(List.of(hit(UUID.randomUUID(), text)), "Widerspruch", 5).get(0).excerpt();

    assertThat(excerpt).contains("Die Widerspruchsfrist beträgt einen Monat.");
    assertThat(excerpt).startsWith("…").endsWith("…");
    // The window plus its two ellipses - the cut is what bounds the answer, not the chunk size.
    assertThat(excerpt.length()).isLessThanOrEqualTo(EXCERPT + 2);
  }

  /**
   * A German question opens with a Fragewort of the same length class as its subject term. The
   * window must follow the subject term, not the first filler word that happens to stand early in
   * the passage - otherwise the excerpt starts at the beginning, exactly as if nothing was found.
   */
  @Test
  void theWindowFollowsTheLongestTermOfTheQuestionAndNotTheFirstFillerWord() {
    String text =
        "Welche Stelle gilt als zuständig, ist in der Hausordnung geregelt. ".repeat(12)
            + "Die Widerspruchsfrist beträgt einen Monat. "
            + "Weitere Hinweise zum Verfahren. ".repeat(12);

    String excerpt =
        digest
            .condense(
                List.of(hit(UUID.randomUUID(), text)), "Welche Frist gilt für den Widerspruch?", 5)
            .get(0)
            .excerpt();

    assertThat(excerpt).contains("Die Widerspruchsfrist beträgt einen Monat.");
    assertThat(excerpt).startsWith("…");
  }

  /**
   * The raw cut of the fallback branch must not split a surrogate pair: a lone surrogate is what
   * the JSON writer refuses, which would turn the tool call into a transport error.
   */
  @Test
  void anUnbrokenTokenOfSupplementaryCharactersIsNeverCutInHalf() {
    // The leading letter makes every pair straddle an odd index, so the raw cut falls inside one.
    String token = "A" + "🙂".repeat(600);

    String excerpt =
        digest.condense(List.of(hit(UUID.randomUUID(), token)), "", 5).get(0).excerpt();

    // A whole pair reads as one supplementary code point; a lone half stays in the surrogate range.
    assertThat(
            excerpt.codePoints().anyMatch(codePoint -> codePoint >= 0xD800 && codePoint <= 0xDFFF))
        .as("no half of a surrogate pair survived the cut")
        .isFalse();
    assertThat(excerpt).endsWith("…");
  }

  /** The position is read in the passage itself, so a longer lower case shifts no window. */
  @Test
  void theWindowIsNotShiftedByCharactersWhoseLowerCaseIsLonger() {
    String text =
        "İ".repeat(300) + "Die Widerspruchsfrist beträgt einen Monat. " + "x y ".repeat(60);

    String excerpt =
        digest
            .condense(List.of(hit(UUID.randomUUID(), text)), "Widerspruchsfrist", 5)
            .get(0)
            .excerpt();

    assertThat(excerpt).contains("Widerspruchsfrist beträgt einen Monat.");
  }

  @Test
  void aVeryLargeMaxHitsAsksForMorePassagesThanASmallOneAndNeverANegativeNumber() {
    assertThat(digest.passagesFor(Integer.MAX_VALUE)).isPositive();
    assertThat(digest.passagesFor(Integer.MAX_VALUE))
        .isGreaterThanOrEqualTo(digest.passagesFor(50));
  }

  @Test
  void aPassageWithoutATermOfTheQuestionYieldsItsBeginning() {
    String text = "Zuständig ist das Bauamt. ".repeat(40);

    String excerpt =
        digest
            .condense(List.of(hit(UUID.randomUUID(), text)), "Kantinenpreise", 5)
            .get(0)
            .excerpt();

    assertThat(excerpt).startsWith("Zuständig ist das Bauamt.").endsWith("…");
    assertThat(excerpt.length()).isLessThanOrEqualTo(EXCERPT + 1);
  }

  @Test
  void passagesOfOneDocumentBecomeOneEntryWithTheBestFirstAndTheRestAsLocations() {
    UUID document = UUID.randomUUID();
    SearchHit best = hit(document, "Erster Fund.");
    SearchHit second = hit(document, "Zweiter Fund.");
    UUID other = UUID.randomUUID();

    List<McpHitDigest.Entry> entries =
        digest.condense(List.of(best, second, hit(other, "Anderes Dokument.")), "Fund", 5);

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).best().hitId()).isEqualTo(best.hitId());
    assertThat(entries.get(0).further())
        .extracting(SearchHit::hitId)
        .containsExactly(second.hitId());
    assertThat(entries.get(1).further()).isEmpty();
  }

  /**
   * The limit counts documents, not passages - otherwise a well covered document crowds out all.
   */
  @Test
  void theLimitCountsDocuments() {
    UUID first = UUID.randomUUID();
    List<SearchHit> hits =
        List.of(
            hit(first, "a"),
            hit(first, "b"),
            hit(UUID.randomUUID(), "c"),
            hit(UUID.randomUUID(), "d"));

    assertThat(digest.condense(hits, "a", 2)).hasSize(2);
    assertThat(digest.passagesFor(5)).isEqualTo(5 * McpHitDigest.PASSAGES_PER_DOCUMENT);
  }

  /** A hit whose document did not resolve stands for itself rather than joining the others. */
  @Test
  void hitsWithoutADocumentAreNeverFoldedTogether() {
    List<SearchHit> hits = List.of(hit(null, "eins"), hit(null, "zwei"));

    assertThat(digest.condense(hits, "eins", 5)).hasSize(2);
  }

  private static SearchHit hit(UUID documentId, String text) {
    return new SearchHit(
        UUID.randomUUID().toString(),
        "Titel",
        text,
        UUID.randomUUID(),
        "Bestand",
        documentId,
        "datei.md",
        0,
        "Abschn. 1",
        List.of(),
        1.0,
        false);
  }
}
