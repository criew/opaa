package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Issue #375: the filesystem path ({@link DocumentService}) and the network path ({@link
 * UrlIndexingExecutor}) used to carry their own extension lists, so the same document was accepted
 * or rejected depending on how it entered the system. Issue #404 replaces the extension-based
 * decision itself with a content-based one on both paths, made through the very same {@link
 * SupportedDocumentFormats#decideForFileName} - so the two paths can no longer drift apart on what
 * "supported" means, by construction rather than by two lists someone has to remember to keep in
 * sync.
 */
class DocumentFormatParityTest {

  @TempDir Path tempDir;

  private static final String PDF_MAGIC_BYTES = "%PDF-1.4\n%mock-pdf-body-for-magic-byte-detection";

  @ParameterizedTest
  @ValueSource(strings = {"handbuch.md", "notiz.txt", "scan.png", "archiv.zip"})
  void bothIndexingPathsDecideAlikeForTheSameContent(String fileName) throws IOException {
    // Plain, human-readable text - accepted regardless of the (possibly misleading) name above.
    Path file = tempDir.resolve(fileName);
    Files.writeString(file, "Ganz gewöhnlicher, lesbarer Text.", StandardCharsets.UTF_8);

    boolean acceptedFromFilesystem = new DocumentService().isSupportedFormat(file);
    boolean acceptedFromNetwork =
        UrlIndexingExecutor.classifyDownloadedFile(file, fileName).supported();

    assertThat(acceptedFromNetwork)
        .as(
            "'%s' must be treated identically by both indexing paths; filesystem says %s, "
                + "network says %s",
            fileName, acceptedFromFilesystem, acceptedFromNetwork)
        .isEqualTo(acceptedFromFilesystem);
  }

  @Test
  void bothIndexingPathsAcceptReadableContentDespiteAWrongExtensionAndReportTheSameMismatch()
      throws IOException {
    // The core case #404 exists for: a real PDF mislabeled .csv used to be rejected outright on
    // both paths - now both accept it and both report the exact same detected extension.
    Path file = tempDir.resolve("bescheid.csv");
    Files.writeString(file, PDF_MAGIC_BYTES, StandardCharsets.UTF_8);

    assertThat(new DocumentService().isSupportedFormat(file)).isTrue();

    var networkDecision = UrlIndexingExecutor.classifyDownloadedFile(file, "bescheid.csv");
    assertThat(networkDecision.supported()).isTrue();
    assertThat(networkDecision.extensionMismatch()).isTrue();
    assertThat(networkDecision.detectedExtension()).isEqualTo(".pdf");
  }

  @Test
  void bothIndexingPathsRejectUnsupportedContentEvenWithASupportedLookingExtension()
      throws IOException {
    Path file = tempDir.resolve("image.pdf");
    Files.write(file, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});

    assertThat(new DocumentService().isSupportedFormat(file)).isFalse();
    assertThat(UrlIndexingExecutor.classifyDownloadedFile(file, "image.pdf").supported()).isFalse();
  }
}
