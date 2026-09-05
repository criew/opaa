package io.opaa.indexing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.document.Document;

/**
 * {@link DocumentPipelineRunner} is the single choke point every caller of {@link
 * DocumentPipeline#run} goes through - covering it here once replaces exercising the same cleanup
 * contract at every one of its call sites.
 */
class DocumentPipelineRunnerTest {

  @TempDir Path tempDir;

  private record FakePipeline(DocumentPipelineResult result) implements DocumentPipeline {

    @Override
    public String id() {
      return "fake";
    }

    @Override
    public short version() {
      return 1;
    }

    @Override
    public Set<String> handledFormats() {
      return Set.of();
    }

    @Override
    public DocumentPipelineResult run(DocumentPipelineSource source) {
      return result;
    }
  }

  private record ThrowingPipeline(RuntimeException toThrow) implements DocumentPipeline {

    @Override
    public String id() {
      return "throwing";
    }

    @Override
    public short version() {
      return 1;
    }

    @Override
    public Set<String> handledFormats() {
      return Set.of();
    }

    @Override
    public DocumentPipelineResult run(DocumentPipelineSource source) {
      throw toThrow;
    }
  }

  @Test
  void deletesEveryDiscoveredAttachmentsTempFileAndReturnsTheResultUnchanged() throws IOException {
    Path attachmentTempFile = tempDir.resolve("attachment.tmp");
    Files.writeString(attachmentTempFile, "bytes");
    var attachment = new DiscoveredAttachment("anlage.pdf", attachmentTempFile, "application/pdf");
    var chunks = List.of(new Document("chunk"));
    var pipeline = new FakePipeline(DocumentPipelineResult.chunked(chunks, List.of(attachment)));

    DocumentPipelineResult result =
        DocumentPipelineRunner.run(pipeline, DocumentPipelineSource.ofExtractedText("text", "f"));

    assertThat(result.chunks()).isEqualTo(chunks);
    assertThat(result.discoveredAttachments()).containsExactly(attachment);
    assertThat(Files.exists(attachmentTempFile)).isFalse();
  }

  /**
   * Every exception a format's parser can raise is the same answer - "could not be read" - and is
   * mapped here rather than in each pipeline, so no caller has to defend against both an outcome
   * and an exception for it.
   */
  @ParameterizedTest
  @MethodSource("pipelineExceptions")
  void anExceptionOutOfAPipelineBecomesAParseFailure(RuntimeException toThrow) {
    var pipeline = new ThrowingPipeline(toThrow);

    DocumentPipelineResult result =
        DocumentPipelineRunner.run(pipeline, DocumentPipelineSource.ofExtractedText("text", "f"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.PARSE_FAILED);
    assertThat(result.chunks()).isEmpty();
    assertThat(result.discoveredAttachments()).isEmpty();
  }

  private static List<RuntimeException> pipelineExceptions() {
    // One per way a pipeline reports a parse failure today: the unchecked wrapper the file-reading
    // pipelines throw (PDF, DOCX, PPTX, ODT, ODP, Tabular), the one HTML/Markdown/Confluence throw
    // for an unreadable source, and an arbitrary runtime failure out of a parser library.
    return List.of(
        new UncheckedIOException(new IOException("corrupt container")),
        new UncheckedIOException("Could not read Markdown document f", new IOException("gone")),
        new IllegalStateException("simulated parser failure"));
  }

  @Test
  void aParseFailureStillReachesTheResultHandler() {
    var pipeline = new ThrowingPipeline(new IllegalStateException("simulated parser failure"));
    List<DocumentPipelineResult> handled = new ArrayList<>();

    DocumentPipelineResult result =
        DocumentPipelineRunner.run(
            pipeline, DocumentPipelineSource.ofExtractedText("text", "f"), handled::add);

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.PARSE_FAILED);
    assertThat(handled).containsExactly(result);
  }

  @Test
  void aFailureToDeleteATempFileNeverTurnsASuccessfulResultIntoAFailure() throws IOException {
    // cleanup catches IOException *and* RuntimeException - deleting a
    // non-empty directory throws DirectoryNotEmptyException (an IOException), the closest
    // reachable stand-in for "deletion fails for a reason unrelated to the pipeline's own result".
    Path directoryTempFile = tempDir.resolve("not-actually-a-file");
    Files.createDirectory(directoryTempFile);
    Files.writeString(directoryTempFile.resolve("child"), "content");
    var attachment = new DiscoveredAttachment("anlage.pdf", directoryTempFile, null);
    var pipeline =
        new FakePipeline(
            DocumentPipelineResult.chunked(List.of(new Document("chunk")), List.of(attachment)));

    DocumentPipelineResult result =
        DocumentPipelineRunner.run(pipeline, DocumentPipelineSource.ofExtractedText("text", "f"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(Files.exists(directoryTempFile)).isTrue();
  }
}
