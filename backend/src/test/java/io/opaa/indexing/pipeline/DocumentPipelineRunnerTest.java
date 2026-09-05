package io.opaa.indexing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

  @Test
  void aPipelineThatThrowsPropagatesWithNothingToCleanUp() {
    // No result was ever produced, so there is nothing DocumentPipelineRunner could have cleaned
    // up - the exception must reach the caller exactly as the pipeline threw it.
    var toThrow = new IllegalStateException("simulated pipeline failure");
    var pipeline = new ThrowingPipeline(toThrow);

    assertThatThrownBy(
            () ->
                DocumentPipelineRunner.run(
                    pipeline, DocumentPipelineSource.ofExtractedText("text", "f")))
        .isSameAs(toThrow);
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
