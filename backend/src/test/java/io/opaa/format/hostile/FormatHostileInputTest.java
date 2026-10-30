package io.opaa.format.hostile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.opaa.format.DiscoveredAttachment;
import io.opaa.format.DocumentFormatRegistry;
import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatResult.Outcome;
import io.opaa.format.DocumentFormatRunner;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.DocumentService;
import io.opaa.format.chunk.ChunkingService;
import io.opaa.format.file.office.OoxmlPackages;
import io.opaa.format.hostile.HostileFiles.HostileFile;
import io.opaa.format.shared.ParserLimitProperties;
import io.opaa.indexing.IndexingProperties;
import io.opaa.test.ProductionDocumentFormats;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

/**
 * Every crafted file of {@link HostileFiles} on the production path of a format - every format the
 * application registers, routed by detected content, run by {@link DocumentFormatRunner} - with the
 * production limits: it ends within a fixed time, as parsed or as {@code PARSE_FAILED}. The {@code
 * hostileInputTest} task runs this class in a JVM of 512 MB heap, so a fix that no longer bounds
 * memory fails as an {@link OutOfMemoryError} here rather than slowing the suite down.
 */
class FormatHostileInputTest {

  private static final Duration BOUND = Duration.ofSeconds(20);

  /** The bound of a case whose fixed state ends in well under a second. */
  private static final Duration SHORT_BOUND = Duration.ofSeconds(5);

  @TempDir Path tempDir;

  private final DocumentFormatRegistry registry = productionRegistry();

  /**
   * What a run yielded: its outcome, its chunks' text, its attachments and the bytes they unpacked
   * to.
   */
  private record Read(Outcome outcome, List<String> chunks, int attachments, long unpackedBytes) {

    String text() {
      return String.join("\n", chunks);
    }

    /** The longest run of characters without whitespace in any chunk. */
    int longestRun() {
      int longest = 0;
      for (String chunk : chunks) {
        int run = 0;
        for (int i = 0; i < chunk.length(); i++) {
          run = Character.isWhitespace(chunk.charAt(i)) ? 0 : run + 1;
          longest = Math.max(longest, run);
        }
      }
      return longest;
    }
  }

  // regression guard for #3336: an outline item that is its own next sibling ends the outline walk
  @Test
  void aPdfWithACyclicOutlineIsReadAlongItsOutlineWithinTheBound() {
    Read read = run(HostileFiles.pdfWithCyclicOutline(), SHORT_BOUND);

    assertThat(read.outcome()).isEqualTo(Outcome.CHUNKED);
    assertThat(read.text()).contains("Kapitel Eins mit seinem Text.");
  }

  // regression guard for #3336: an XML part unpacking past the part limit fails before the DOM
  @Test
  void aDocxCompressionBombFailsAtThePartLimitBeforeItIsUnpacked() {
    HostileFile bomb = HostileFiles.docxCompressionBomb(40L * 1024 * 1024);
    Path file = bomb.writeTo(tempDir);

    assertTimeoutPreemptively(
        SHORT_BOUND,
        () ->
            assertThatThrownBy(() -> OoxmlPackages.open(file, ParserLimitProperties.DEFAULTS))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("word/document.xml exceeds the configured size limit"));
    assertThat(run(bomb, SHORT_BOUND).outcome()).isEqualTo(Outcome.PARSE_FAILED);
  }

  // regression guard: a message nesting messages hands out only its own level (ADR-0022)
  @Test
  void aMessageNestingMessagesIsReadOneLevelAtATime() {
    Read read = run(HostileFiles.emlWithNestedMessages(8, 30), BOUND);

    assertThat(read.outcome()).isEqualTo(Outcome.CHUNKED);
    // the thirty text attachments and the one nested message of the top level
    assertThat(read.attachments()).isEqualTo(31);
  }

  // regression guard for #3376: attachment entries sharing sectors unpack to no more than the file
  @Test
  void anOutlookMessageWithSharedAttachmentSectorsUnpacksToNoMoreThanItsSize() {
    HostileFile file = HostileFiles.msgWithSharedAttachmentSectors(64 * 1024, 30, 512);

    Read read = run(file, BOUND);

    assertThat(read.outcome()).isEqualTo(Outcome.CHUNKED);
    assertThat(read.attachments()).isPositive();
    assertThat(read.unpackedBytes()).isLessThanOrEqualTo(file.bytes().length);
  }

  // regression guard for #3375: a title is cut to the label length before the URL cleanup scans it
  @Test
  void anHtmlTitleThatMadeTheUrlCleanupBacktrackIsCutBeforeTheScan() {
    assertThat(run(HostileFiles.htmlWithBacktrackingTitle(33_000), SHORT_BOUND).outcome())
        .isEqualTo(Outcome.CHUNKED);
  }

  // regression guard for #3375: the URL cleanup of a paragraph runs in linear time
  @Test
  void aParagraphThatMadeTheUrlCleanupBacktrackIsReadWithinTheBound() {
    assertThat(run(HostileFiles.htmlWithBacktrackingParagraph(330_000), SHORT_BOUND).outcome())
        .isEqualTo(Outcome.CHUNKED);
  }

  // regression guard for #3375: a Markdown heading is matched without lazy backtracking
  @Test
  void aMarkdownHeadingThatMadeThePatternBacktrackIsReadWithinTheBound() {
    assertThat(run(HostileFiles.markdownWithBacktrackingHeading(200_000), SHORT_BOUND).outcome())
        .isEqualTo(Outcome.CHUNKED);
  }

  // regression guard for #3375: a table collects only its own rows, to a bounded nesting depth
  @Test
  void deeplyNestedWideTablesAreReadWithinTheBound() {
    Read read = run(HostileFiles.htmlWithNestedTables(120, 1_000), SHORT_BOUND);

    assertThat(read.outcome()).isEqualTo(Outcome.CHUNKED);
    assertThat(read.text()).contains("Innerste Zelle");
  }

  // regression guard for #3375: membership in the content roots is no list scan
  @Test
  void manyContentRootsAreSelectedWithinTheBound() {
    Read read = run(HostileFiles.htmlWithManyContentRoots(150_000), BOUND);

    assertThat(read.outcome()).isEqualTo(Outcome.CHUNKED);
    assertThat(read.text()).doesNotContain("Fuß");
  }

  // regression guard for #3376: a run without whitespace is cut before it is tokenised
  @Test
  void textWithoutWhitespaceIsCutIntoBoundedRuns() {
    Read read = run(HostileFiles.textWithoutWhitespace(6_000_000), BOUND);

    assertThat(read.outcome()).isEqualTo(Outcome.CHUNKED);
    assertThat(read.longestRun()).isLessThanOrEqualTo(ChunkingService.MAX_RUN_CHARACTERS);
  }

  // regression guard for #3376: the fallback parses no embedded object, however often it is pooled
  @Test
  void aWordDocumentsPooledObjectsAreNotParsed() {
    Read read = run(HostileFiles.docWithPackagedTextInItsObjectPool(20), BOUND);

    assertThat(read.outcome()).isEqualTo(Outcome.CHUNKED);
    assertThat(read.text()).doesNotContain(HostileFiles.EMBEDDED_TEXT);
  }

  /** The PPTX format reads the text of slide shapes and notes and never opens an embedded part. */
  @Test
  void aPresentationsEmbeddedDocumentsAreNotRead() {
    Read read = run(HostileFiles.pptxEmbeddingAWordDocumentPerSlide(20), BOUND);

    assertThat(read.outcome()).isEqualTo(Outcome.CHUNKED);
    assertThat(read.text()).contains("Folie 19").doesNotContain(HostileFiles.EMBEDDED_TEXT);
  }

  @Disabled("#3434")
  @Test
  void aPdfDrawingOneFormOverAndOverFailsAtTheTextLimit() {
    assertThat(run(HostileFiles.pdfDrawingOneFormOverAndOver(11_000), BOUND).outcome())
        .isEqualTo(Outcome.PARSE_FAILED);
  }

  private Read run(HostileFile hostile, Duration bound) {
    Path file = hostile.writeTo(tempDir);
    return assertTimeoutPreemptively(
        bound,
        () -> {
          DocumentFormatRegistry.Routed routed =
              registry.routedPipelineFor(file, hostile.fileName());
          long[] unpacked = {0};
          DocumentFormatResult result =
              DocumentFormatRunner.run(
                  routed.pipeline(),
                  DocumentFormatSource.ofFile(file, hostile.fileName(), routed.detectedExtension()),
                  // measured before the runner deletes the attachments' temp files
                  parsed -> {
                    for (DiscoveredAttachment attachment : parsed.discoveredAttachments()) {
                      unpacked[0] += sizeOf(attachment.tempFile());
                    }
                  });
          return new Read(
              result.outcome(),
              result.chunks().stream().map(Document::getText).toList(),
              result.discoveredAttachments().size(),
              unpacked[0]);
        },
        hostile.fileName());
  }

  private static long sizeOf(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Every format the application registers, from the list {@code
   * DocumentFormatRegistryRoutingIntegrationTest} holds against the wired context, with the default
   * limits.
   */
  private static DocumentFormatRegistry productionRegistry() {
    return ProductionDocumentFormats.registry(
        new DocumentService(),
        new ChunkingService(new IndexingProperties(0, 0, 0, null, null, null, 0)));
  }
}
