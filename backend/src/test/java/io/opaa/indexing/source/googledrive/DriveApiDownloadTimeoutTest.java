package io.opaa.indexing.source.googledrive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.security.TargetAddressValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The download timeout of {@link DriveApi} covers the whole body: a host that keeps trickling bytes
 * is cut off at the deadline as a transient failure and leaves no temp file.
 */
class DriveApiDownloadTimeoutTest {

  private FakeDriveServer server;

  @BeforeEach
  void setUp() {
    server = new FakeDriveServer();
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  @Test
  void aBodyTricklingInPastTheDownloadTimeoutIsCutOffAndLeavesNoTempFile() throws Exception {
    FakeDriveServer.Item slow =
        server.file(
            "slow", "Langsam.txt", "text/plain", FakeDriveServer.ROOT_ID, null, new byte[300]);
    slow.trickle = true;
    DriveApi api = api(Duration.ofSeconds(1));
    Set<String> before = tempFiles();
    long start = System.nanoTime();

    Throwable thrown =
        catchThrowable(() -> api.download("files/slow", Map.of("alt", "media"), 1024));

    assertThat(thrown).isInstanceOf(DriveApiException.class);
    assertThat(((DriveApiException) thrown).kind()).isEqualTo(DriveApiException.Kind.TRANSIENT);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    assertThat(tempFiles()).isEqualTo(before);
  }

  @Test
  void aBodyWithinTheDownloadTimeoutIsDownloaded() throws Exception {
    server.file("fast", "Schnell.txt", "text/plain", FakeDriveServer.ROOT_ID, null, "Inhalt");
    DriveApi api = api(Duration.ofSeconds(30));

    Path file = api.download("files/fast", Map.of("alt", "media"), 1024);

    try {
      assertThat(Files.readString(file)).isEqualTo("Inhalt");
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private DriveApi api(Duration downloadTimeout) {
    GoogleDriveProperties properties =
        new GoogleDriveProperties(0, 0, null, downloadTimeout, null, null, 0, 0, 0, null);
    SourceSettings settings =
        new SourceSettings(null, server.base().toString(), null, null, false, null);
    return new DriveApiFactory(properties, TargetAddressValidator.disabled(), wait -> {})
        .open(settings, () -> FakeDriveServer.TOKEN, RequestBudget.unbounded());
  }

  private static Set<String> tempFiles() throws Exception {
    try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
      return files
          .map(file -> file.getFileName().toString())
          .filter(name -> name.startsWith("opaa-gdrive-"))
          .collect(Collectors.toSet());
    }
  }
}
