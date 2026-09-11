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
   * A text without markers must come back byte-identical: the harness scripts marker-free short
   * answers and relies on taking the same path production takes.
   */
  @Test
  void aTextWithoutMarkersIsReturnedUnchanged() {
    String answer = "Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr.";

    assertThat(CitationMarkers.strip(answer)).isEqualTo(answer);
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
