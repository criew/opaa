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

  @Test
  void doesNotTreatAnInteriorPeriodAsTheExtensionBoundary() {
    // #940 review, finding 2: only a trailing, extension-shaped suffix is stripped - an interior
    // period (not itself immediately followed by 1-5 alphanumeric characters at the string's end)
    // stays part of the title instead of being mistaken for the extension separator.
    assertThat(ChunkContextTitle.deriveTitle("001_bericht.januar.pdf")).isEqualTo("bericht.januar");
  }

  @Test
  void leavesATrailingSuffixThatIsTooLongToBeAnExtensionInPlace() {
    assertThat(ChunkContextTitle.deriveTitle("protokoll.endgueltig"))
        .isEqualTo("protokoll.endgueltig");
  }

  @Test
  void capsTheDerivedTitleAtEightTokens() {
    assertThat(
            ChunkContextTitle.deriveTitle(
                "001_ein_sehr_langer_dateiname_mit_vielen_einzelnen_woertern_die_alle_ueberleben_wollen.txt"))
        .isEqualTo("ein sehr langer dateiname mit vielen einzelnen woertern");
  }
}
