package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentServiceTest {

  private final DocumentService service = new DocumentService();

  @TempDir Path tempDir;

  // A PDF's magic string alone ("%PDF-") is enough for Tika's own magic-byte detection to report
  // application/pdf, without a fully valid PDF structure.
  private static final String PDF_MAGIC_BYTES = "%PDF-1.4\n%mock-pdf-body-for-magic-byte-detection";

  @Test
  void discoverFilesFindsSupported() throws IOException {
    Files.writeString(tempDir.resolve("readme.md"), "# Hello");
    Files.writeString(tempDir.resolve("notes.txt"), "Some notes");
    // Binary garbage that Tika cannot resolve to any of the accepted content types.
    Files.write(
        tempDir.resolve("data.csv"), new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0, 1, 2, 3});

    var discovered = service.discoverFiles(tempDir);

    assertThat(discovered.supported())
        .extracting(p -> p.getFileName().toString())
        .containsExactlyInAnyOrder("readme.md", "notes.txt");
    // Issue #375: the rejected file is handed back, not swallowed by the filter.
    assertThat(discovered.rejected())
        .extracting(p -> p.getFileName().toString())
        .containsOnly("data.csv");
    assertThat(discovered.totalFound()).isEqualTo(3);
    assertThat(discovered.mismatches()).isEmpty();
  }

  @Test
  void discoverFilesHandlesNestedDirectories() throws IOException {
    Path subDir = tempDir.resolve("subdir");
    Files.createDirectory(subDir);
    Files.writeString(subDir.resolve("deep.md"), "# Deep");
    Files.writeString(tempDir.resolve("top.txt"), "Top");

    assertThat(service.discoverFiles(tempDir).supported()).hasSize(2);
  }

  @Test
  void discoverFilesFailsInsteadOfSilentlyReportingAnEmptyBestandForANonexistentDir() {
    // #886 review: a missing directory (unmounted network share, moved/renamed source) must fail
    // the run, not look like a genuinely empty - but successful - source;
    // StaleDocumentCleanupService
    // would otherwise read that as "every document vanished" and delete the whole library.
    Path nonexistent = tempDir.resolve("nonexistent");

    assertThatThrownBy(() -> service.discoverFiles(nonexistent))
        .isInstanceOf(IOException.class)
        .hasMessageContaining(nonexistent.toString());
  }

  @Test
  void discoverFilesFailsWhenThePathIsAFileNotADirectory() throws IOException {
    Path file = tempDir.resolve("not-a-directory.txt");
    Files.writeString(file, "content");

    assertThatThrownBy(() -> service.discoverFiles(file)).isInstanceOf(IOException.class);
  }

  @Test
  void discoverFilesReturnsEmptyForEmptyDir() throws IOException {
    var discovered = service.discoverFiles(tempDir);

    assertThat(discovered.supported()).isEmpty();
    assertThat(discovered.rejected()).isEmpty();
  }

  // --- #404: content decides, the extension is only a hint ------------------------------------

  @Test
  void discoverFilesAcceptsReadableContentDespiteAWrongExtension() throws IOException {
    // The core case #404 exists for: a real PDF mislabeled with an unsupported extension used to
    // be rejected outright, even though Tika can read it perfectly well.
    Path file = tempDir.resolve("bescheid.csv");
    Files.writeString(file, PDF_MAGIC_BYTES, StandardCharsets.UTF_8);

    var discovered = service.discoverFiles(tempDir);

    assertThat(discovered.supported()).containsExactly(file);
    assertThat(discovered.rejected()).isEmpty();
    assertThat(discovered.mismatches())
        .extracting(DocumentService.FormatMismatch::detectedExtension)
        .containsExactly(".pdf");
  }

  @Test
  void discoverFilesReportsNoMismatchWhenExtensionMatchesContent() throws IOException {
    Path file = tempDir.resolve("bescheid.pdf");
    Files.writeString(file, PDF_MAGIC_BYTES, StandardCharsets.UTF_8);

    var discovered = service.discoverFiles(tempDir);

    assertThat(discovered.supported()).containsExactly(file);
    assertThat(discovered.mismatches()).isEmpty();
  }

  @Test
  void discoverFilesRejectsUnsupportedContentEvenWithASupportedLookingExtension()
      throws IOException {
    // A file wearing a supported extension whose content Tika cannot resolve to any accepted
    // type must still be rejected - the extension alone is never enough to accept it.
    Path file = tempDir.resolve("image.pdf");
    Files.write(file, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});

    var discovered = service.discoverFiles(tempDir);

    assertThat(discovered.rejected()).containsExactly(file);
    assertThat(discovered.supported()).isEmpty();
  }

  @Test
  void isSupportedFormatAcceptsAFileWhoseContentMatchesAnAcceptedType() throws IOException {
    Path file = tempDir.resolve("doc.pdf");
    Files.writeString(file, PDF_MAGIC_BYTES, StandardCharsets.UTF_8);

    assertThat(service.isSupportedFormat(file)).isTrue();
  }

  @Test
  void isSupportedFormatRejectsUnsupportedContent() throws IOException {
    Path file = tempDir.resolve("image.png");
    Files.write(file, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});

    assertThat(service.isSupportedFormat(file)).isFalse();
  }

  @Test
  void isSupportedFormatTreatsAnUnreadableFileAsUnsupported() {
    Path missing = tempDir.resolve("does-not-exist.pdf");

    assertThat(service.isSupportedFormat(missing)).isFalse();
  }

  @Test
  void parseDocumentExtractsTextFromMd() throws IOException {
    Path file = tempDir.resolve("test.md");
    Files.writeString(file, "# Title\n\nSome content here.");

    var result = service.parseDocument(file);

    assertThat(result).isNotEmpty();
    assertThat(result.getFirst().getText()).contains("Title");
    assertThat(result.getFirst().getText()).contains("Some content here");
  }

  @Test
  void parseDocumentExtractsTextFromTxt() throws IOException {
    Path file = tempDir.resolve("test.txt");
    Files.writeString(file, "Plain text content for testing.");

    var result = service.parseDocument(file);

    assertThat(result).isNotEmpty();
    assertThat(result.getFirst().getText()).contains("Plain text content");
  }

  // --- #1055: scan-PDF detection -----------------------------------------------------------

  @Test
  void isTextlessPdfDetectsAPdfWhoseParsedDocumentsCarryOnlyBlankText() throws IOException {
    Path file = tempDir.resolve("scan.pdf");
    Files.writeString(file, PDF_MAGIC_BYTES, StandardCharsets.UTF_8);

    var parsed = List.of(new org.springframework.ai.document.Document(""));

    assertThat(service.isTextlessPdf(file, parsed)).isTrue();
  }

  @Test
  void isTextlessPdfDetectsAPdfWithNoParsedDocumentsAtAll() throws IOException {
    Path file = tempDir.resolve("empty-parse.pdf");
    Files.writeString(file, PDF_MAGIC_BYTES, StandardCharsets.UTF_8);

    assertThat(service.isTextlessPdf(file, List.of())).isTrue();
  }

  @Test
  void isTextlessPdfIsFalseWhenAtLeastOneParsedDocumentCarriesText() throws IOException {
    Path file = tempDir.resolve("has-text.pdf");
    Files.writeString(file, PDF_MAGIC_BYTES, StandardCharsets.UTF_8);

    var parsed =
        List.of(
            new org.springframework.ai.document.Document(""),
            new org.springframework.ai.document.Document("actual content"));

    assertThat(service.isTextlessPdf(file, parsed)).isFalse();
  }

  @Test
  void isTextlessPdfIsFalseForATextlessNonPdfFile() throws IOException {
    // The rule is scoped to PDF (ingestion-pipelines.md, Teil 3, Punkt 1) - blank text from any
    // other format is left to the existing generic "no content extracted" handling.
    Path file = tempDir.resolve("blank.txt");
    Files.writeString(file, "", StandardCharsets.UTF_8);

    var parsed = List.of(new org.springframework.ai.document.Document(""));

    assertThat(service.isTextlessPdf(file, parsed)).isFalse();
  }
}
