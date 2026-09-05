package io.opaa.indexing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The deduplication a DOCX header/footer and an ODF master page share; the non-breaking space cases
 * are the ones an authority letterhead actually produces.
 */
class DeduplicatedLinesTest {

  @Test
  void aLineRepeatedVerbatimIsKeptOnceInFirstSeenOrder() {
    DeduplicatedLines lines = new DeduplicatedLines();

    lines.add("Stadt Musterstadt");
    lines.add("Az. 12-34/2026");
    lines.add("Stadt Musterstadt");

    assertThat(lines.text()).isEqualTo("Stadt Musterstadt\nAz. 12-34/2026");
  }

  @Test
  void aVariantSeparatedByANonBreakingSpaceIsTheSameLine() {
    // regression guard: \s alone matches neither U+00A0 nor U+202F, so both variants would
    // otherwise survive as separate lines of the same letterhead.
    DeduplicatedLines lines = new DeduplicatedLines();

    lines.add("Stadt Musterstadt · Amt 12");
    lines.add("Stadt Musterstadt · Amt 12");
    lines.add("Stadt Musterstadt · Amt 12");

    assertThat(lines.text()).isEqualTo("Stadt Musterstadt · Amt 12");
  }

  @Test
  void aVariantDifferingOnlyInWhitespaceRunsIsTheSameLine() {
    DeduplicatedLines lines = new DeduplicatedLines();

    lines.add("Stadt Musterstadt");
    lines.add("  Stadt   Musterstadt  ");

    assertThat(lines.text()).isEqualTo("Stadt Musterstadt");
  }

  @Test
  void aBlankOrNullLineIsIgnored() {
    DeduplicatedLines lines = new DeduplicatedLines();

    lines.add(null);
    lines.add("   ");
    lines.add("\t\n");

    assertThat(lines.isEmpty()).isTrue();
    assertThat(lines.text()).isEmpty();
  }

  @Test
  void aCollectedLineIsStoredStripped() {
    DeduplicatedLines lines = new DeduplicatedLines();

    lines.add("  Az. 12-34/2026\t");

    assertThat(lines.text()).isEqualTo("Az. 12-34/2026");
  }
}
