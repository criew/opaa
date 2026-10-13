package io.opaa.format;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.format.file.fallback.TikaFallbackFormat;
import io.opaa.format.file.mail.MailDocumentFormat;
import io.opaa.format.file.markdown.MarkdownDocumentFormat;
import io.opaa.format.shared.HeadingSectionSplitter;
import io.opaa.format.shared.HeadingSectionSplitterFixture;
import io.opaa.test.ProductionDocumentFormats;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The chunking fingerprint records the target size of the structure-aware cut on every chunk, so a
 * changed {@code opaa.indexing.chunk-target-characters} makes the chunks cut under the old value
 * selectable for the re-index - and only for the formats whose cut depends on it.
 */
class ChunkingFingerprintTest {

  private static final HeadingSectionSplitter SMALLER_TARGET = new HeadingSectionSplitter(2_000);

  @Test
  void everyFormatOnTheStructureAwareCutRecordsItsTargetSize() {
    List<DocumentFormat> onTheCut =
        ProductionDocumentFormats.formats().stream().filter(f -> !independentOfTarget(f)).toList();

    assertThat(onTheCut).hasSizeGreaterThanOrEqualTo(9);
    assertThat(onTheCut)
        .allSatisfy(
            format -> {
              assertThat(format.chunkingFingerprint())
                  .as(format.id())
                  .isNotNull()
                  .isEqualTo(HeadingSectionSplitterFixture.DEFAULT.chunkingFingerprint());
              assertThat(format.unrecordedChunkingFingerprint())
                  .as(format.id())
                  .isEqualTo(HeadingSectionSplitter.UNRECORDED_CHUNKING_FINGERPRINT);
            });
  }

  @Test
  void theFallbackAndTheMailFormatRecordNothingTheirCutDoesNotDependOnTheTargetSize() {
    List<DocumentFormat> independent =
        ProductionDocumentFormats.formats().stream()
            .filter(ChunkingFingerprintTest::independentOfTarget)
            .toList();

    assertThat(independent).hasSize(2);
    assertThat(independent)
        .allSatisfy(
            format -> {
              assertThat(format.chunkingFingerprint()).as(format.id()).isNull();
              assertThat(format.cutUnderOtherConfiguration(SMALLER_TARGET.chunkingFingerprint()))
                  .as(format.id())
                  .isFalse();
              assertThat(format.cutUnderOtherConfiguration(null)).as(format.id()).isFalse();
            });
  }

  @Test
  void theFingerprintFollowsTheConfiguredTargetSize() {
    MarkdownDocumentFormat smaller = new MarkdownDocumentFormat(SMALLER_TARGET);

    assertThat(SMALLER_TARGET.chunkingFingerprint())
        .isNotNull()
        .isNotEqualTo(HeadingSectionSplitterFixture.DEFAULT.chunkingFingerprint());
    assertThat(smaller.chunkingFingerprint()).isEqualTo(SMALLER_TARGET.chunkingFingerprint());
  }

  @Test
  void aChunkCutUnderAnotherTargetSizeIsCutUnderAnotherConfiguration() {
    MarkdownDocumentFormat smaller = new MarkdownDocumentFormat(SMALLER_TARGET);
    MarkdownDocumentFormat byDefault =
        new MarkdownDocumentFormat(HeadingSectionSplitterFixture.DEFAULT);

    assertThat(smaller.cutUnderOtherConfiguration(byDefault.chunkingFingerprint())).isTrue();
    assertThat(byDefault.cutUnderOtherConfiguration(smaller.chunkingFingerprint())).isTrue();
    assertThat(smaller.cutUnderOtherConfiguration(smaller.chunkingFingerprint())).isFalse();
  }

  @Test
  void aChunkWithoutRecordedFingerprintCountsAsCutAtTheDefaultTargetSize() {
    // Before the fingerprint was written the target size was fixed at the default: an upgrade at
    // the default leaves the bestand current, a changed target size makes it due.
    assertThat(new MarkdownDocumentFormat(HeadingSectionSplitterFixture.DEFAULT))
        .satisfies(format -> assertThat(format.cutUnderOtherConfiguration(null)).isFalse());
    assertThat(new MarkdownDocumentFormat(SMALLER_TARGET))
        .satisfies(format -> assertThat(format.cutUnderOtherConfiguration(null)).isTrue());
  }

  private static boolean independentOfTarget(DocumentFormat format) {
    return format instanceof TikaFallbackFormat || format instanceof MailDocumentFormat;
  }
}
