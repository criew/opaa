package io.opaa.format.hostile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.opaa.format.DiscoveredAttachment;
import io.opaa.format.DocumentFormat;
import io.opaa.format.DocumentFormatRegistry;
import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatResult.Outcome;
import io.opaa.format.DocumentFormatRunner;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.DocumentService;
import io.opaa.format.chunk.ChunkingService;
import io.opaa.format.file.fallback.TikaFallbackFormat;
import io.opaa.format.file.html.HtmlDocumentFormat;
import io.opaa.format.file.mail.MailDocumentFormat;
import io.opaa.format.file.mail.MailProperties;
import io.opaa.format.file.markdown.MarkdownDocumentFormat;
import io.opaa.format.file.office.DocxDocumentFormat;
import io.opaa.format.file.pdf.PdfDocumentFormat;
import io.opaa.format.hostile.HostileFiles.HostileFile;
import io.opaa.format.shared.HeadingSectionSplitterFixture;
import io.opaa.indexing.IndexingProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every crafted file of {@link HostileFiles} on the production path of a format - routed by its
 * detected content, run by {@link DocumentFormatRunner} - with the production limits: it ends
 * within a fixed time, as parsed or as {@code PARSE_FAILED}. The {@code hostileInputTest} task runs
 * this class in a JVM of 512 MB heap, so a fix that no longer bounds memory fails as an {@link
 * OutOfMemoryError} here rather than slowing the suite down.
 */
class FormatHostileInputTest {

  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path tempDir;

  private final DocumentFormatRegistry registry = productionRegistry();

  /** What a run yielded: its outcome, its attachments and the bytes they unpacked to. */
  private record Read(Outcome outcome, int attachments, long unpackedBytes) {}

  // regression guard for #3336: an outline item that is its own next sibling ends the outline walk
  @Test
  void aPdfWithACyclicOutlineIsReadWithinTheBound() {
    assertThat(run(HostileFiles.pdfWithCyclicOutline()).outcome()).isEqualTo(Outcome.CHUNKED);
  }

  // regression guard for #3336: an XML part unpacking past the part limit fails before the DOM
  @Test
  void aDocxCompressionBombFailsBeforeItIsUnpacked() {
    assertThat(run(HostileFiles.docxCompressionBomb(40L * 1024 * 1024)).outcome())
        .isEqualTo(Outcome.PARSE_FAILED);
  }

  // regression guard for #3376: a message nesting messages hands out only its own level
  @Test
  void aMessageNestingMessagesIsReadOneLevelAtATime() {
    Read read = run(HostileFiles.emlWithNestedMessages(8, 30));

    assertThat(read.outcome()).isEqualTo(Outcome.CHUNKED);
    // the thirty text attachments and the one nested message of the top level
    assertThat(read.attachments()).isEqualTo(31);
  }

  // regression guard for #3376: attachment entries sharing sectors unpack to no more than the file
  @Test
  void anOutlookMessageWithSharedAttachmentSectorsUnpacksToNoMoreThanItsSize() {
    HostileFile file = HostileFiles.msgWithSharedAttachmentSectors(64 * 1024, 30, 512);

    Read read = run(file);

    assertThat(read.outcome()).isEqualTo(Outcome.CHUNKED);
    assertThat(read.attachments()).isPositive();
    assertThat(read.unpackedBytes()).isLessThanOrEqualTo(file.bytes().length);
  }

  // regression guard for #3375: the URL cleanup of a title runs in linear time
  @Test
  void anHtmlTitleThatMadeTheUrlCleanupBacktrackIsReadWithinTheBound() {
    assertThat(run(HostileFiles.htmlWithBacktrackingTitle(33_000)).outcome())
        .isEqualTo(Outcome.CHUNKED);
  }

  // regression guard for #3375: a Markdown heading is matched without lazy backtracking
  @Test
  void aMarkdownHeadingThatMadeThePatternBacktrackIsReadWithinTheBound() {
    assertThat(run(HostileFiles.markdownWithBacktrackingHeading(200_000)).outcome())
        .isEqualTo(Outcome.CHUNKED);
  }

  // regression guard for #3375: nested tables are walked by their direct rows, to a bounded depth
  @Test
  void tablesNestedThousandsDeepAreReadWithinTheBound() {
    assertThat(run(HostileFiles.htmlWithNestedTables(50_000)).outcome()).isEqualTo(Outcome.CHUNKED);
  }

  // regression guard for #3376: a run without whitespace is cut before it is tokenised
  @Test
  void textWithoutWhitespaceIsChunkedWithinTheHeap() {
    assertThat(run(HostileFiles.textWithoutWhitespace(6_000_000)).outcome())
        .isEqualTo(Outcome.CHUNKED);
  }

  private Read run(HostileFile hostile) {
    Path file = hostile.writeTo(tempDir);
    return assertTimeoutPreemptively(
        BOUND,
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
          return new Read(result.outcome(), result.discoveredAttachments().size(), unpacked[0]);
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

  /** The formats a crafted file of {@link HostileFiles} reaches, with the production limits. */
  private static DocumentFormatRegistry productionRegistry() {
    ChunkingService chunking =
        new ChunkingService(new IndexingProperties(0, 0, 0, null, null, null, 0));
    DocumentFormat fallback = new TikaFallbackFormat(new DocumentService(), chunking);
    List<DocumentFormat> formats =
        List.of(
            fallback,
            new HtmlDocumentFormat(HeadingSectionSplitterFixture.DEFAULT),
            new MarkdownDocumentFormat(HeadingSectionSplitterFixture.DEFAULT),
            new DocxDocumentFormat(HeadingSectionSplitterFixture.DEFAULT),
            new PdfDocumentFormat(HeadingSectionSplitterFixture.DEFAULT),
            new MailDocumentFormat(chunking, new MailProperties(0, 0, 0), Clock.systemUTC()));
    return new DocumentFormatRegistry(formats, fallback);
  }
}
