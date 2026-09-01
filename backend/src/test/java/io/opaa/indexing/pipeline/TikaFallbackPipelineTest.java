package io.opaa.indexing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.DocumentService;
import io.opaa.indexing.IndexingProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    assertThatThrownBy(() -> new DocumentPipelineSource("x.txt", null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new DocumentPipelineSource("x.txt", tempDir.resolve("x.txt"), "text", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- #1057: ODF routes through this same fallback (no dedicated pipeline exists yet for its
  // Microsoft-format counterparts either), so the fallback's Tika reader is the actual extractor
  // exercised for ODT/ODS/ODP -----------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"odt", "ods", "odp"})
  void extractsAndChunksTextFromEveryOdfFormat(String extension) throws IOException {
    Path file = copyTestResource("test-document." + extension, "vermerk." + extension);

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, file.getFileName().toString()));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    assertThat(result.chunks()).isNotEmpty();
    assertThat(result.chunks())
        .anyMatch(
            chunk -> chunk.getText() != null && chunk.getText().toLowerCase().contains("opaa"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"odt", "ods", "odp"})
  void anEmptyOdfDocumentIsReportedAsHavingNoExtractableTextRatherThanIndexedWithZeroChunks(
      String extension) throws IOException {
    // #1055 guard: a format that parses successfully but yields no usable content must not end up
    // INDEXED with zero chunks - it must be rejected the same way a scan PDF is.
    Path file = copyTestResource("empty-document." + extension, "leer." + extension);

    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofFile(file, file.getFileName().toString()));

    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
    assertThat(result.chunks()).isEmpty();
  }

  private Path copyTestResource(String resourceName, String targetFileName) throws IOException {
    Path target = tempDir.resolve(targetFileName);
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("test-documents/" + resourceName)) {
      assertThat(in).as("Test resource %s must exist", resourceName).isNotNull();
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return target;
  }
}
