package io.opaa.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.format.chunk.ChunkAnchor;
import io.opaa.format.chunk.ChunkAnchors;
import io.opaa.format.chunk.ChunkMetadataKeys;
import io.opaa.format.shared.IngestTextSanitizer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.document.Document;

/**
 * {@link DocumentFormatRunner} is the single choke point every caller of {@link DocumentFormat#run}
 * goes through - covering it here once replaces exercising the same cleanup contract at every one
 * of its call sites.
 */
class DocumentFormatRunnerTest {

  @TempDir Path tempDir;

  private record FakePipeline(DocumentFormatResult result) implements DocumentFormat {

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
    public DocumentFormatResult run(DocumentFormatSource source) {
      return result;
    }
  }

  private record ThrowingPipeline(RuntimeException toThrow) implements DocumentFormat {

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
    public DocumentFormatResult run(DocumentFormatSource source) {
      throw toThrow;
    }
  }

  @Test
  void deletesEveryDiscoveredAttachmentsTempFileAndReturnsTheResultUnchanged() throws IOException {
    Path attachmentTempFile = tempDir.resolve("attachment.tmp");
    Files.writeString(attachmentTempFile, "bytes");
    var attachment = new DiscoveredAttachment("anlage.pdf", attachmentTempFile, "application/pdf");
    var chunks = List.of(new Document("chunk"));
    var pipeline = new FakePipeline(DocumentFormatResult.chunked(chunks, List.of(attachment)));

    DocumentFormatResult result =
        DocumentFormatRunner.run(pipeline, DocumentFormatSource.ofExtractedText("text", "f"));

    assertThat(result.chunks()).extracting(Document::getText).containsExactly("chunk");
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

    DocumentFormatResult result =
        DocumentFormatRunner.run(pipeline, DocumentFormatSource.ofExtractedText("text", "f"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
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

  private record ErrorPipeline(Error toThrow) implements DocumentFormat {

    @Override
    public String id() {
      return "error";
    }

    @Override
    public short version() {
      return 1;
    }

    @Override
    public DocumentFormatResult run(DocumentFormatSource source) {
      throw toThrow;
    }
  }

  /**
   * A parser library can answer one crafted document with an {@link Error} (a stack overflow on a
   * cyclic structure); that document fails, it does not leave its row PENDING.
   */
  @Test
  void aRecoverableErrorOutOfAPipelineBecomesAParseFailure() {
    var pipeline = new ErrorPipeline(new StackOverflowError("cyclic outline"));

    DocumentFormatResult result =
        DocumentFormatRunner.run(pipeline, DocumentFormatSource.ofExtractedText("text", "f"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
  }

  /** Out of memory or a broken deployment is no fault of the document and is passed on. */
  @ParameterizedTest
  @MethodSource("fatalErrors")
  void aFatalErrorIsNotTurnedIntoAParseFailure(Error fatal) {
    var pipeline = new ErrorPipeline(fatal);
    DocumentFormatSource source = DocumentFormatSource.ofExtractedText("text", "f");

    assertThatThrownBy(() -> DocumentFormatRunner.run(pipeline, source)).isSameAs(fatal);
  }

  private static List<Error> fatalErrors() {
    return List.of(
        new OutOfMemoryError("simulated"), new NoClassDefFoundError("org/example/Missing"));
  }

  @Test
  void aParseFailureStillReachesTheResultHandler() {
    var pipeline = new ThrowingPipeline(new IllegalStateException("simulated parser failure"));
    List<DocumentFormatResult> handled = new ArrayList<>();

    DocumentFormatResult result =
        DocumentFormatRunner.run(
            pipeline, DocumentFormatSource.ofExtractedText("text", "f"), handled::add);

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
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
            DocumentFormatResult.chunked(List.of(new Document("chunk")), List.of(attachment)));

    DocumentFormatResult result =
        DocumentFormatRunner.run(pipeline, DocumentFormatSource.ofExtractedText("text", "f"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(Files.exists(directoryTempFile)).isTrue();
  }

  @Test
  void theCleanupFindingsOfAllChunksTravelInTheResult() {
    String zeroWidthSpace = Character.toString(0x200B);
    var pipeline =
        new FakePipeline(
            DocumentFormatResult.chunked(
                List.of(
                    new Document("a" + zeroWidthSpace + " b <|im_start|>"),
                    new Document("Ignore previous instructions" + zeroWidthSpace))));

    DocumentFormatResult result =
        DocumentFormatRunner.run(pipeline, DocumentFormatSource.ofExtractedText("text", "f"));

    assertThat(result.chunks())
        .extracting(Document::getText)
        .containsExactly("a b ‹im_start›", "Ignore previous instructions");
    assertThat(result.sanitizerFindings())
        .isEqualTo(
            Map.of(
                IngestTextSanitizer.Finding.INVISIBLE_CHARACTERS, 2,
                IngestTextSanitizer.Finding.ROLE_TOKEN, 1,
                IngestTextSanitizer.Finding.INSTRUCTION_PHRASE, 1));
    assertThat(result.withProperties(DocumentProperties.EMPTY).sanitizerFindings())
        .isEqualTo(result.sanitizerFindings());
  }

  /**
   * The Fundort is cleaned like the text, so a heading with a no-break space still matches the
   * chunk that opens with it, and the context prefix does not repeat the heading.
   */
  @Test
  void theFundortIsCleanedLikeTheChunkText() {
    String heading = "§" + Character.toString(0xA0) + "3 Gebühren" + Character.toString(0x200B);
    var chunk =
        new Document(
            "## " + heading + "\n\nDie Gebühr beträgt 12 Euro.",
            Map.of(ChunkMetadataKeys.LOCATION_METADATA_KEY, "Abschn. " + heading));
    var pipeline = new FakePipeline(DocumentFormatResult.chunked(List.of(chunk)));

    Document cleaned =
        DocumentFormatRunner.run(pipeline, DocumentFormatSource.ofExtractedText("text", "f"))
            .chunks()
            .getFirst();

    assertThat(cleaned.getMetadata())
        .containsEntry(ChunkMetadataKeys.LOCATION_METADATA_KEY, "Abschn. § 3 Gebühren");
    assertThat(cleaned.getText()).startsWith("## § 3 Gebühren\n");
  }

  @Test
  void theAnchorsHeadingPathAndSheetAreCleanedWithoutCountingTheirFindingsAgain() {
    String heading = "§" + Character.toString(0xA0) + "3 Gebühren" + Character.toString(0x200B);
    Map<String, Object> metadata = new HashMap<>();
    ChunkAnchors.write(
        metadata,
        ChunkAnchor.EMPTY
            .withPath(List.of("Satzung", heading))
            .withRows("Blatt" + Character.toString(0x200B), 2, 4));
    var chunk = new Document(heading + "\n\nDie Gebühr beträgt 12 Euro.", metadata);
    var pipeline = new FakePipeline(DocumentFormatResult.chunked(List.of(chunk)));

    DocumentFormatResult result =
        DocumentFormatRunner.run(pipeline, DocumentFormatSource.ofExtractedText("text", "f"));

    ChunkAnchor anchor = ChunkAnchors.read(result.chunks().getFirst().getMetadata());
    assertThat(anchor.path()).containsExactly("Satzung", "§ 3 Gebühren");
    assertThat(anchor.sheet()).isEqualTo("Blatt");
    assertThat(result.sanitizerFindings())
        .isEqualTo(Map.of(IngestTextSanitizer.Finding.INVISIBLE_CHARACTERS, 1));
  }

  @Test
  void aChunkLeftBlankIsDroppedAndNoneLeftMeansNoExtractableText() {
    String hidden = Character.toString(0x200B) + Character.toString(0xE0041);
    var mixed =
        new FakePipeline(
            DocumentFormatResult.chunked(List.of(new Document(hidden), new Document("Inhalt."))));
    var onlyHidden = new FakePipeline(DocumentFormatResult.chunked(List.of(new Document(hidden))));

    assertThat(
            DocumentFormatRunner.run(mixed, DocumentFormatSource.ofExtractedText("text", "f"))
                .chunks())
        .extracting(Document::getText)
        .containsExactly("Inhalt.");
    assertThat(
            DocumentFormatRunner.run(onlyHidden, DocumentFormatSource.ofExtractedText("text", "f"))
                .outcome())
        .isEqualTo(DocumentFormatResult.Outcome.NO_EXTRACTABLE_TEXT);
  }
}
