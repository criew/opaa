package io.opaa.indexing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class DocumentPipelineResultTest {

  @Test
  void discoveredAttachmentsIsEmptyForEveryFactoryMethodThatDoesNotTakeIt() {
    // ADR-0022, part 2: the neutrality guarantee - a pipeline that never reports an attachment
    // (every pipeline as of this PR) gets an empty list from every existing factory method.
    var chunks = List.of(new Document("text"));
    assertThat(DocumentPipelineResult.chunked(chunks).discoveredAttachments()).isEmpty();
    assertThat(DocumentPipelineResult.noContent().discoveredAttachments()).isEmpty();
    assertThat(DocumentPipelineResult.noExtractableText().discoveredAttachments()).isEmpty();
  }

  @Test
  void chunkedWithAttachmentsCarriesBothChunksAndDiscoveredAttachments() {
    var chunks = List.of(new Document("text"));
    var attachment =
        new DiscoveredAttachment("anlage.pdf", Path.of("/tmp/anlage.pdf"), "application/pdf");

    DocumentPipelineResult result = DocumentPipelineResult.chunked(chunks, List.of(attachment));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).isEqualTo(chunks);
    assertThat(result.discoveredAttachments()).containsExactly(attachment);
  }

  @Test
  void aNullDiscoveredAttachmentsListIsNormalizedToEmptyLikeChunksAlreadyIsForOlderCallers() {
    var result = new DocumentPipelineResult(DocumentPipelineResult.Outcome.NO_CONTENT, null, null);

    assertThat(result.chunks()).isEmpty();
    assertThat(result.discoveredAttachments()).isEmpty();
  }
}
