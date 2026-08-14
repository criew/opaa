package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentServiceTest {

  private final DocumentService service = new DocumentService();

  @TempDir Path tempDir;

  @Test
  void discoverFilesFindsSupported() throws IOException {
    Files.writeString(tempDir.resolve("readme.md"), "# Hello");
    Files.writeString(tempDir.resolve("notes.txt"), "Some notes");
    Files.writeString(tempDir.resolve("data.csv"), "a,b,c");

    var discovered = service.discoverFiles(tempDir);

    assertThat(discovered.supported()).hasSize(2);
    assertThat(discovered.supported())
        .extracting(p -> p.getFileName().toString())
        .containsOnly("readme.md", "notes.txt");
    // Issue #375: the rejected file is handed back, not swallowed by the filter.
    assertThat(discovered.rejected())
        .extracting(p -> p.getFileName().toString())
        .containsOnly("data.csv");
    assertThat(discovered.totalFound()).isEqualTo(3);
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
  void discoverFilesReturnsEmptyForNonexistentDir() throws IOException {
    var discovered = service.discoverFiles(tempDir.resolve("nonexistent"));

    assertThat(discovered.supported()).isEmpty();
    assertThat(discovered.rejected()).isEmpty();
  }

  @Test
  void discoverFilesReturnsEmptyForEmptyDir() throws IOException {
    var discovered = service.discoverFiles(tempDir);

    assertThat(discovered.supported()).isEmpty();
    assertThat(discovered.rejected()).isEmpty();
  }

  @Test
  void isSupportedFormatAcceptsValidExtensions() {
    assertThat(service.isSupportedFormat(Path.of("doc.md"))).isTrue();
    assertThat(service.isSupportedFormat(Path.of("doc.txt"))).isTrue();
    assertThat(service.isSupportedFormat(Path.of("doc.pdf"))).isTrue();
    assertThat(service.isSupportedFormat(Path.of("doc.docx"))).isTrue();
    assertThat(service.isSupportedFormat(Path.of("doc.pptx"))).isTrue();
    // Issue #375: legacy .doc used to be accepted on the network path only.
    assertThat(service.isSupportedFormat(Path.of("doc.doc"))).isTrue();
  }

  @Test
  void isSupportedFormatRejectsUnsupported() {
    assertThat(service.isSupportedFormat(Path.of("image.png"))).isFalse();
    assertThat(service.isSupportedFormat(Path.of("data.csv"))).isFalse();
    assertThat(service.isSupportedFormat(Path.of("code.java"))).isFalse();
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
