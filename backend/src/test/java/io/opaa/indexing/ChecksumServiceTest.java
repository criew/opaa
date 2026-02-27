package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChecksumServiceTest {

  private final ChecksumService checksumService = new ChecksumService();

  @Test
  void computesCorrectSha256ForKnownContent(@TempDir Path tempDir) throws IOException {
    Path file = tempDir.resolve("test.txt");
    Files.writeString(file, "hello world");

    String checksum = checksumService.computeSha256(file);

    // Known SHA-256 of "hello world"
    assertThat(checksum)
        .isEqualTo("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9");
  }

  @Test
  void sameContentProducesSameChecksum(@TempDir Path tempDir) throws IOException {
    Path file1 = tempDir.resolve("file1.txt");
    Path file2 = tempDir.resolve("file2.txt");
    Files.writeString(file1, "identical content");
    Files.writeString(file2, "identical content");

    assertThat(checksumService.computeSha256(file1))
        .isEqualTo(checksumService.computeSha256(file2));
  }

  @Test
  void differentContentProducesDifferentChecksum(@TempDir Path tempDir) throws IOException {
    Path file1 = tempDir.resolve("file1.txt");
    Path file2 = tempDir.resolve("file2.txt");
    Files.writeString(file1, "content A");
    Files.writeString(file2, "content B");

    assertThat(checksumService.computeSha256(file1))
        .isNotEqualTo(checksumService.computeSha256(file2));
  }

  @Test
  void throwsIOExceptionForNonExistentFile(@TempDir Path tempDir) {
    Path nonExistent = tempDir.resolve("does-not-exist.txt");

    assertThatThrownBy(() -> checksumService.computeSha256(nonExistent))
        .isInstanceOf(IOException.class);
  }

  @Test
  void returnsHexStringOf64Characters(@TempDir Path tempDir) throws IOException {
    Path file = tempDir.resolve("test.txt");
    Files.writeString(file, "any content");

    String checksum = checksumService.computeSha256(file);

    assertThat(checksum).hasSize(64);
    assertThat(checksum).matches("[0-9a-f]{64}");
  }
}
