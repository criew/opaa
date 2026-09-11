package io.opaa.query.citation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Citation-marker removal on the way into the conversation window (#1486). */
class CitationMarkersTest {

  @Test
  void aMarkerAtTheEndOfASentenceIsRemovedWithTheSpaceItLeavesBehind() {
    String answer =
        "Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr. "
            + "【source: 3fa85f64-5717-4562-b3fc-2c963f66afa6#0 | anwohnerparken.md】";

    assertThat(CitationMarkers.strip(answer))
        .isEqualTo("Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr.");
  }

  @Test
  void everyMarkerOfAMultiSourceAnswerIsRemoved() {
    String answer =
        "Erstens gilt A 【source: doc-1#0 | a.md】. Zweitens gilt B 【source: doc-2#3 | b.md】.";

    assertThat(CitationMarkers.strip(answer)).isEqualTo("Erstens gilt A . Zweitens gilt B .");
  }

  /** Markers removed mid-line must not leave a double space behind. */
  @Test
  void aMarkerRemovedMidSentenceLeavesASingleSpace() {
    String answer = "Der Satz 【source: doc-1#0 | a.md】 geht weiter.";

    assertThat(CitationMarkers.strip(answer)).isEqualTo("Der Satz geht weiter.");
  }

  @Test
  void aMarkerAtTheEndOfALineDoesNotLeaveTrailingWhitespace() {
    String answer = "Zeile eins 【source: doc-1#0 | a.md】\nZeile zwei.";

    assertThat(CitationMarkers.strip(answer)).isEqualTo("Zeile eins\nZeile zwei.");
  }

  /**
   * A text without markers must come back byte-identical - and that has to hold for the texts an
   * answer actually contains, not only for a one-line sentence: the indentation of a YAML block,
   * the nesting of a list and the two trailing spaces of a hard Markdown line break all carry
   * meaning the next turn is answered against. The harness relies on the same identity when it
   * appends a scripted, marker-free short answer.
   */
  @Test
  void aTextWithoutMarkersIsReturnedUnchanged() {
    String answer =
        "Die Konfiguration sieht so aus:\n"
            + "\n"
            + "```yaml\n"
            + "opaa:\n"
            + "  query:\n"
            + "    top-k: 8\n"
            + "    fetch-k: 25\n"
            + "```\n"
            + "\n"
            + "- Erster Punkt\n"
            + "  - Untergeordneter Punkt\n"
            + "- Zweiter Punkt\n"
            + "\n"
            + "Erste Zeile eines harten Umbruchs  \n"
            + "zweite Zeile.\n";

    assertThat(CitationMarkers.strip(answer)).isEqualTo(answer);
  }

  /** A marker inside an indented block takes its own whitespace, never the indentation. */
  @Test
  void aMarkerInAnIndentedBlockLeavesTheIndentationAlone() {
    String answer =
        "Die Werte lauten:\n"
            + "  - top-k: 8 【source: doc-1#0 | a.md】\n"
            + "    - fetch-k: 25\n"
            + "  - mmr-lambda: 1,0";

    assertThat(CitationMarkers.strip(answer))
        .isEqualTo(
            "Die Werte lauten:\n"
                + "  - top-k: 8\n"
                + "    - fetch-k: 25\n"
                + "  - mmr-lambda: 1,0");
  }

  /** Two markers in a row are one removal, not two - no space is left between them. */
  @Test
  void aRunOfMarkersIsRemovedAsOne() {
    String answer = "Beides gilt 【source: doc-1#0 | a.md】【source: doc-2#1 | b.md】 und weiter.";

    assertThat(CitationMarkers.strip(answer)).isEqualTo("Beides gilt und weiter.");
  }

  /** A marker glued between two characters leaves nothing where it stood. */
  @Test
  void aMarkerWithoutSurroundingSpaceLeavesNoSpace() {
    String answer = "A【source: doc-1#0 | a.md】B";

    assertThat(CitationMarkers.strip(answer)).isEqualTo("AB");
  }

  /** A marker at the start of a line does not push the line one space to the right. */
  @Test
  void aMarkerAtTheStartOfALineLeavesNoLeadingSpace() {
    String answer = "Zeile eins.\n【source: doc-1#0 | a.md】 Zeile zwei.";

    assertThat(CitationMarkers.strip(answer)).isEqualTo("Zeile eins.\nZeile zwei.");
  }

  /**
   * Whitespace at the start of a line is the line's indentation, not the marker's - a marker that
   * opens an indented line must not pull the line back to column zero.
   */
  @Test
  void aMarkerOpeningAnIndentedLineKeepsTheIndentation() {
    String answer = "Die Werte lauten:\n  【source: doc-1#0 | a.md】 top-k: 8\n    fetch-k: 25";

    assertThat(CitationMarkers.strip(answer))
        .isEqualTo("Die Werte lauten:\n  top-k: 8\n    fetch-k: 25");
  }

  /** Something that only looks like a marker is not one - nothing is guessed away. */
  @Test
  void aMalformedMarkerIsLeftAlone() {
    String answer = "Siehe 【Quelle: a.md】 und 【source: a.md】.";

    assertThat(CitationMarkers.strip(answer)).isEqualTo(answer);
  }

  @Test
  void nullAndEmptyPassThrough() {
    assertThat(CitationMarkers.strip(null)).isNull();
    assertThat(CitationMarkers.strip("")).isEmpty();
  }
}
