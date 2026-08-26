package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChunkContextTitleTest {

  @Test
  void stripsANumericIndexPrefix() {
    assertThat(ChunkContextTitle.deriveTitle("001_personalausweis.md"))
        .isEqualTo("personalausweis");
  }

  @Test
  void stripsATwoDigitIndexPrefix() {
    assertThat(ChunkContextTitle.deriveTitle("01_verwaltungsgebuehrensatzung.pdf"))
        .isEqualTo("verwaltungsgebuehrensatzung");
  }

  @Test
  void stripsATagPlusNumberPrefix() {
    assertThat(ChunkContextTitle.deriveTitle("city-0022_prag.md")).isEqualTo("prag");
  }

  @Test
  void leavesAFileNameWithoutAStructuralPrefixUnchanged() {
    assertThat(ChunkContextTitle.deriveTitle("report.pdf")).isEqualTo("report");
  }

  @Test
  void replacesRemainingSeparatorsWithSpaces() {
    assertThat(ChunkContextTitle.deriveTitle("02_gebuehrenbefreiung-beduerftigkeit.docx"))
        .isEqualTo("gebuehrenbefreiung beduerftigkeit");
  }

  @Test
  void fallsBackToTheExtensionStrippedNameWhenEverythingLooksStructural() {
    // A pathological all-digits file name: stripping would leave nothing to embed, so the
    // extension-stripped name itself is kept rather than an empty title.
    assertThat(ChunkContextTitle.deriveTitle("12345.pdf")).isEqualTo("12345");
  }

  @Test
  void handlesAFileNameWithoutAnExtension() {
    assertThat(ChunkContextTitle.deriveTitle("001_readme")).isEqualTo("readme");
  }

  @Test
  void handlesMultipleRemainingWordsAfterTheStructuralPrefix() {
    assertThat(ChunkContextTitle.deriveTitle("019_anwohnerparkausweissatzung.pdf"))
        .isEqualTo("anwohnerparkausweissatzung");
  }
}
