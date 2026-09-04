package io.opaa.indexing.pipeline;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.DocumentService;
import io.opaa.indexing.IndexingProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
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

  // A PDF's magic string alone ("%PDF-") is enough for Tika's own magic-byte detection to report
  // application/pdf, without a fully valid PDF structure.
  private static final String PDF_MAGIC_BYTES = "%PDF-1.4\n%mock-pdf-body-for-magic-byte-detection";

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
    assertThatThrownBy(() -> new DocumentPipelineSource("x.txt", null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new DocumentPipelineSource("x.txt", tempDir.resolve("x.txt"), "text", null, null))
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
    // A real Tika-parsed chunk here also carries the reader's own parser metadata (e.g. "source",
    // via OverlappingTokenTextSplitter's HashMap copy of the document's metadata) - keys outside
    // the registry-wide passthrough union, which this guard ignores: only a produced key that IS
    // part of the union must be declared, since only a union key can ever ride along.
    Set<String> actualKeysInUnion =
        result.chunks().stream()
            .flatMap(c -> c.getMetadata().keySet().stream())
            .filter(PassthroughMetadataKeysTestSupport.REGISTRY_UNION::contains)
            .collect(toSet());
    assertThat(pipeline.passthroughMetadataKeys()).containsAll(actualKeysInUnion);
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

  // --- #1055/#1117: scan-PDF detection, moved here from DocumentServiceTest along with
  // isTextlessPdf itself (only this pipeline ever asks it) ------------------------------------

  @Test
  void isTextlessPdfDetectsAPdfWhoseParsedDocumentsCarryOnlyBlankText() throws IOException {
    Path file = tempDir.resolve("scan.pdf");
    Files.writeString(file, PDF_MAGIC_BYTES, StandardCharsets.UTF_8);

    var parsed = List.of(new org.springframework.ai.document.Document(""));

    assertThat(pipeline.isTextlessPdf(file, parsed)).isTrue();
  }

  @Test
  void isTextlessPdfDetectsAPdfWithNoParsedDocumentsAtAll() throws IOException {
    Path file = tempDir.resolve("empty-parse.pdf");
    Files.writeString(file, PDF_MAGIC_BYTES, StandardCharsets.UTF_8);

    assertThat(pipeline.isTextlessPdf(file, List.of())).isTrue();
  }

  @Test
  void isTextlessPdfIsFalseWhenAtLeastOneParsedDocumentCarriesText() throws IOException {
    Path file = tempDir.resolve("has-text.pdf");
    Files.writeString(file, PDF_MAGIC_BYTES, StandardCharsets.UTF_8);

    var parsed =
        List.of(
            new org.springframework.ai.document.Document(""),
            new org.springframework.ai.document.Document("actual content"));

    assertThat(pipeline.isTextlessPdf(file, parsed)).isFalse();
  }

  @Test
  void isTextlessPdfIsFalseForATextlessNonPdfFile() throws IOException {
    // The rule is scoped to PDF (ingestion-pipelines.md, Teil 3, Punkt 1) - blank text from any
    // other format is left to the existing generic "no content extracted" handling.
    Path file = tempDir.resolve("blank.txt");
    Files.writeString(file, "", StandardCharsets.UTF_8);

    var parsed = List.of(new org.springframework.ai.document.Document(""));

    assertThat(pipeline.isTextlessPdf(file, parsed)).isFalse();
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
