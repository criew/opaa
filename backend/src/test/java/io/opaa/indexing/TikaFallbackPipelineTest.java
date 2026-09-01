package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fallback pipeline reproduces the pre-abstraction ingest exactly (#1056): the same Tika
 * reader, the same token splitter with the same globally configured chunk size, and the same three
 * outcomes {@code FileProcessingService} used to decide itself - now decided by the pipeline, so a
 * format-specific pipeline can decide them differently for its own format.
 */
class TikaFallbackPipelineTest {

  @TempDir Path tempDir;

  private final IndexingProperties properties =
      new IndexingProperties(1000, 100, 50, null, null, List.of(), null, null, null, 1);
  private final DocumentService documentService = new DocumentService();
  private final ChunkingService chunkingService = new ChunkingService(properties);
  private final TikaFallbackPipeline pipeline =
      new TikaFallbackPipeline(documentService, chunkingService);

  @Test
  void claimsNoFormatSoEveryFormatKeepsReachingIt() {
    assertThat(pipeline.handledFormats()).isEmpty();
    assertThat(pipeline.id()).isEqualTo("tika-fallback");
    assertThat(pipeline.version()).isEqualTo((short) 1);
  }

  @Test
  void producesTheSameChunksTheReaderAndSplitterProduceOnTheirOwn() throws IOException {
    Path file = tempDir.resolve("vermerk.txt");
    Files.writeString(file, "Die Verwaltungsgebühr beträgt 37,00 EUR. ".repeat(200));

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "vermerk.txt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    // Verhaltensneutral: byte-identical to calling the two services directly, which is what the
    // chunk-invariant eval dry runs still do.
    List<String> expected =
        chunkingService.chunkDocuments("vermerk.txt", documentService.parseDocument(file)).stream()
            .map(org.springframework.ai.document.Document::getText)
            .toList();
    assertThat(result.chunks().stream().map(org.springframework.ai.document.Document::getText))
        .containsExactlyElementsOf(expected);
  }

  @Test
  void alreadyExtractedTextIsChunkedWithoutBeingParsedAgain() {
    DocumentPipelineResult result =
        pipeline.run(
            DocumentPipelineSource.ofExtractedText(
                "Ratsinformationssystem: Beschluss über die Gebührensatzung.", "Beschluss"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).hasSize(1);
  }

  @Test
  void textThatChunksDownToNothingIsReportedAsHavingNoExtractableText() throws IOException {
    Path file = tempDir.resolve("rauschen.txt");
    // Below ChunkingService's own minChunkLengthToEmbed - non-blank, yet no chunk survives.
    Files.writeString(file, "a b");

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, "rauschen.txt"));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
    assertThat(result.chunks()).isEmpty();
  }

  @Test
  void aSourceMustCarryEitherAFileOrExtractedTextButNeverBoth() {
    assertThatThrownBy(() -> new DocumentPipelineSource("x.txt", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DocumentPipelineSource("x.txt", tempDir.resolve("x.txt"), "text"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
