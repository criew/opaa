package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Docker-free unit tests for {@link CorpusManifest} (issue #227 review follow-up). Run via the
 * {@code evalUnitTest} Gradle task, part of {@code check}.
 */
class CorpusManifestTest {

  @TempDir Path corpusDir;

  @Test
  void verifyAcceptsAMatchingManifest() throws IOException {
    Path file = writeCorpusFile("doc1.md", "content one");
    Path manifest = writeManifestFile(hashLine(file, "doc1.md"));

    CorpusManifest.VerificationResult result = CorpusManifest.verify(corpusDir, manifest);

    assertThat(result.isValid()).isTrue();
    assertThat(result.fileNames()).containsExactly("doc1.md");
  }

  @Test
  void verifyFlagsAManipulatedByte() throws IOException {
    Path file = writeCorpusFile("doc1.md", "content one");
    Path manifest = writeManifestFile(hashLine(file, "doc1.md"));
    // Manipulate one byte after the manifest was captured.
    Files.writeString(file, "content ONE", StandardCharsets.UTF_8);

    CorpusManifest.VerificationResult result = CorpusManifest.verify(corpusDir, manifest);

    assertThat(result.isValid()).isFalse();
    assertThat(result.violations()).hasSize(1);
    assertThat(result.violations().getFirst().fileName()).isEqualTo("doc1.md");
    assertThat(result.violations().getFirst().reason()).contains("checksum mismatch");
  }

  @Test
  void verifyFlagsAFileListedButMissingFromTheCorpus() throws IOException {
    Path manifest =
        writeManifestFile(
            "0000000000000000000000000000000000000000000000000000000000000000 *ghost.md");

    CorpusManifest.VerificationResult result = CorpusManifest.verify(corpusDir, manifest);

    assertThat(result.isValid()).isFalse();
    assertThat(result.violations().getFirst().reason()).contains("missing from corpus");
  }

  @Test
  void verifyFlagsAMalformedManifestLine() throws IOException {
    Path manifest = writeManifestFile("not-a-valid-manifest-line");

    CorpusManifest.VerificationResult result = CorpusManifest.verify(corpusDir, manifest);

    assertThat(result.isValid()).isFalse();
    assertThat(result.violations().getFirst().reason()).contains("malformed manifest line");
  }

  @Test
  void verifyIgnoresBlankLines() throws IOException {
    Path file = writeCorpusFile("doc1.md", "content one");
    Path manifest = writeManifestFile(hashLine(file, "doc1.md"), "", "   ");

    CorpusManifest.VerificationResult result = CorpusManifest.verify(corpusDir, manifest);

    assertThat(result.isValid()).isTrue();
    assertThat(result.fileNames()).containsExactly("doc1.md");
  }

  private Path writeCorpusFile(String name, String content) throws IOException {
    Path file = corpusDir.resolve(name);
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  private Path writeManifestFile(String... lines) throws IOException {
    Path manifest = corpusDir.resolve("MANIFEST.sha256");
    Files.write(manifest, java.util.List.of(lines), StandardCharsets.UTF_8);
    return manifest;
  }

  private String hashLine(Path file, String fileName) throws IOException {
    return CorpusManifest.sha256Hex(file) + " *" + fileName;
  }
}
