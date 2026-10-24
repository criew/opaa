package io.opaa.query.citation;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.format.shared.IngestTextSanitizer;
import org.junit.jupiter.api.Test;

/**
 * The fact check compares an answer with the stored chunk text, and the stored text is the cleaned
 * one: a figure that carried invisible characters in the source is still found as cited.
 */
class CitationFactCheckSanitizedTextTest {

  @Test
  void aFigureWithRemovedInvisibleCharactersIsStillSupported() {
    String zeroWidthSpace = Character.toString(0x200B);
    String source =
        "Die Gebühr beträgt 3"
            + zeroWidthSpace
            + "7,00 Euro, fällig zum 1"
            + Character.toString(0xE0041)
            + "5.03.2026.";
    String stored = IngestTextSanitizer.sanitize(source).text();

    assertThat(stored).isEqualTo("Die Gebühr beträgt 37,00 Euro, fällig zum 15.03.2026.");
    assertThat(
            CitationFactChecker.isSupportedByChunk(
                "Die Gebühr beträgt 37,00 Euro und ist am 15.03.2026 fällig.", stored))
        .isTrue();
  }
}
