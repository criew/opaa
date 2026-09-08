package io.opaa.indexing.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

/**
 * The base every file-only format shares: one read per call, the fileless guard once, and a read
 * failure answered as a parse failure in {@link DocumentFormat#run} but as {@link
 * DocumentProperties#EMPTY} in {@link DocumentFormat#readProperties}.
 */
class FileDocumentFormatTest {

  @TempDir Path tempDir;

  private static final class CountingPipeline extends FileDocumentFormat<String> {

    private final AtomicInteger reads = new AtomicInteger();
    private final IOException readFailure;

    private CountingPipeline(IOException readFailure) {
      this.readFailure = readFailure;
    }

    @Override
    public String id() {
      return "counting";
    }

    @Override
    public short version() {
      return 1;
    }

    @Override
    public Set<FormatAdmission> admittedFormats() {
      return Set.of(FormatAdmission.detectedAs(".counting", "application/x-counting"));
    }

    @Override
    protected String read(DocumentFormatSource source) throws IOException {
      reads.incrementAndGet();
      if (readFailure != null) {
        throw readFailure;
      }
      return Files.readString(source.file());
    }

    @Override
    protected DocumentFormatResult chunks(DocumentFormatSource source, String content) {
      return DocumentFormatResult.chunked(List.of(new Document(content)));
    }

    @Override
    protected DocumentProperties properties(String content) {
      return DocumentProperties.EMPTY.withTitleLine(content);
    }
  }

  @Test
  void oneRunReadsTheFileExactlyOnceAndDerivesBothChunksAndPropertiesFromThatRead()
      throws IOException {
    Path file = tempDir.resolve("satzung.txt");
    Files.writeString(file, "Satzung der Stadt Musterstadt");
    var pipeline = new CountingPipeline(null);

    DocumentFormatResult result =
        pipeline.run(DocumentFormatSource.ofFile(file, "satzung.txt", ".txt"));

    assertThat(pipeline.reads).hasValue(1);
    assertThat(result.chunks()).hasSize(1);
    assertThat(result.properties().titleLine()).isEqualTo("Satzung der Stadt Musterstadt");
  }

  @Test
  void readingPropertiesAloneReadsTheFileOnceAndProducesNoChunks() throws IOException {
    Path file = tempDir.resolve("satzung.txt");
    Files.writeString(file, "Satzung der Stadt Musterstadt");
    var pipeline = new CountingPipeline(null);

    DocumentProperties properties =
        pipeline.readProperties(DocumentFormatSource.ofFile(file, "satzung.txt", ".txt"));

    assertThat(pipeline.reads).hasValue(1);
    assertThat(properties.titleLine()).isEqualTo("Satzung der Stadt Musterstadt");
  }

  @Test
  void aReadFailureIsThrownOutOfRunAndAnsweredAsEmptyPropertiesByReadProperties() {
    Path file = tempDir.resolve("kaputt.txt");
    var pipeline = new CountingPipeline(new IOException("corrupt container"));
    DocumentFormatSource source = DocumentFormatSource.ofFile(file, "kaputt.txt", ".txt");

    // Thrown, not caught: DocumentFormatRunner is the single place that maps it to PARSE_FAILED.
    assertThatThrownBy(() -> pipeline.run(source)).hasRootCauseMessage("corrupt container");
    assertThat(DocumentFormatRunner.run(pipeline, source).outcome())
        .isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
    assertThat(pipeline.readProperties(source)).isEqualTo(DocumentProperties.EMPTY);
  }

  @Test
  void aSourceWithoutAFileIsAParseFailureAndHasNoProperties() {
    var pipeline = new CountingPipeline(null);
    DocumentFormatSource source = DocumentFormatSource.ofExtractedText("text", "quelle.txt");

    assertThat(pipeline.run(source).outcome()).isEqualTo(DocumentFormatResult.Outcome.PARSE_FAILED);
    assertThat(pipeline.readProperties(source)).isEqualTo(DocumentProperties.EMPTY);
    assertThat(pipeline.reads).hasValue(0);
  }
}
