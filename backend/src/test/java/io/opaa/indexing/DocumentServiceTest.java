package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Set;
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
    // The rejected file is handed back, not swallowed by the filter.
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
    // a missing directory (unmounted network share, moved/renamed source) must fail
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

  // --- content decides, the extension is only a hint ------------------------------------

  @Test
  void discoverFilesAcceptsReadableContentDespiteAWrongExtension() throws IOException {
    // The core case content-based admission exists for: a real PDF mislabeled with an
    // unsupported extension is accepted, because Tika can read it perfectly well.
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
  void discoverFilesTreatsAFileItCannotReadAsUnsupported() throws IOException {
    // A file whose bytes cannot be read at all (permission-denied, or deleted between the walk and
    // the detection) counts as unsupported rather than failing the whole run with an IOException.
    Path file = tempDir.resolve("unlesbar.pdf");
    Files.writeString(file, PDF_MAGIC_BYTES, StandardCharsets.UTF_8);
    assumeTrue(
        Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class),
        "needs POSIX permissions to make a file unreadable");
    Files.setPosixFilePermissions(file, Set.of());
    assumeTrue(!Files.isReadable(file), "needs a genuinely unreadable file, so not as root");

    var discovered = service.discoverFiles(tempDir);

    assertThat(discovered.rejected()).containsExactly(file);
    assertThat(discovered.supported()).isEmpty();
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
}
