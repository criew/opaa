package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ChunkContextPrefix} (#1072): the format of the Kontextpräfix, that an empty segment is
 * left out entirely, and which Fundort counts as Strukturkontext.
 */
class ChunkContextPrefixTest {

  @Test
  void joinsTitleMetadataAndStructureContextInTheOrderOfTheSpecification() {
    String prefix =
        ChunkContextPrefix.build(
            "Verwaltungsgebührensatzung",
            List.of("Fassung 2026", "Kommune"),
            "§ 7 Gebühren für Personaldokumente");

    assertThat(prefix)
        .isEqualTo(
            "Verwaltungsgebührensatzung › Fassung 2026 › Kommune › §"
                + " 7 Gebühren für Personaldokumente");
  }

  @Test
  void leavesOutEverySegmentThatCarriesNothingAndYieldsNullWithoutAnySegment() {
    assertThat(ChunkContextPrefix.build("Satzung", Arrays.asList(null, "  ", "Fassung 2026"), null))
        .isEqualTo("Satzung › Fassung 2026");
    assertThat(ChunkContextPrefix.build(null, List.of(), "  ")).isNull();
    assertThat(ChunkContextPrefix.build(null, List.of(), "§ 7")).isEqualTo("§ 7");
  }

  @Test
  void takesTheStructureContextOnlyFromASectionFundort() {
    assertThat(ChunkContextPrefix.structureContextFrom("Abschn. § 7 Gebühren"))
        .isEqualTo("§ 7 Gebühren");
    assertThat(ChunkContextPrefix.structureContextFrom("S. 2–4"))
        .as("a page number names no content")
        .isNull();
    assertThat(ChunkContextPrefix.structureContextFrom(null)).isNull();
    assertThat(ChunkContextPrefix.structureContextFrom(42)).isNull();
  }

  @Test
  void formatsThePrefixInBracketsAheadOfTheUntouchedChunkText() {
    assertThat(ChunkContextPrefix.format("Satzung › Fassung 2026", "37,00 EUR"))
        .isEqualTo("[Satzung › Fassung 2026]\n\n37,00 EUR");
  }
}
