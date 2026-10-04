package io.opaa.indexing.source.smb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.indexing.filesync.FileSyncHarness;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.security.TargetAddressValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A link loop the server reports instead of resolving it ({@code follow symlinks = no} on the share
 * {@code verweise}: {@code a -> b -> a}) through the real chain of smbj, transport and store: smbj
 * re-opens the target of every link without a limit, the transport ends the chain, and only the
 * path that runs through the loop is affected - not a parallel download, not the run.
 */
@Testcontainers(disabledWithoutDocker = true)
class SmbLinkLoopTest {

  private final SambaFixture samba = SambaFixture.get();

  @Test
  void aPathThroughALoopIsALinkFindingAndTheClientWorksOn() throws Exception {
    try (SmbShareClient smb = client()) {
      assertThatThrownBy(() -> smb.download("a/x.txt", "x.txt", 1000))
          .isInstanceOf(SmbAccessException.Link.class)
          .hasMessageContaining("Verknüpfungen (Schleife)");

      Path file = smb.download("echt/datei.txt", "datei.txt", 1000);
      try {
        assertThat(Files.readString(file)).isEqualTo("Echt\n");
      } finally {
        Files.deleteIfExists(file);
      }
    }
  }

  @Test
  void aLoopEndsOnlyItsOwnAreaNotTheRun() {
    SourceSettings settings =
        new SourceSettings(
            null,
            samba.url(SambaFixture.LINK_SHARE),
            null,
            samba.credentials(),
            false,
            ConnectorData.of(Map.of("folders", List.of("/a/unten", "/echt"))));

    FileSyncHarness.Run run;
    try {
      run = new FileSyncHarness().fullSync(SmbTestStores.open(settings, 10));
    } catch (Exception e) {
      throw new AssertionError(e);
    }

    assertThat(run.failure()).isNull();
    assertThat(run.ingested())
        .containsExactly(samba.url(SambaFixture.LINK_SHARE) + "/echt/datei.txt");
    assertThat(run.unlistedContainerKeys()).containsExactly("/a/unten");
    assertThat(run.eventsOf(IndexingEventCategory.REJECTED))
        .extracting(IndexingRunEvent::getMessage)
        .anySatisfy(message -> assertThat(message).contains("Verknüpfungen (Schleife)"));
  }

  @Test
  void aDownloadInFlightWhileALoopDropsTheConnectionIsRepeatedNotLost() throws Exception {
    samba.bigFileInLinkShare("echt/gross.bin", 200);
    try (SmbShareClient smb = client()) {
      smb.connect();
      int baseline = smb.meter().requests();
      CompletableFuture<Path> download =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return smb.download("echt/gross.bin", "gross.bin", 300L * 1024 * 1024);
                } catch (Exception e) {
                  throw new IllegalStateException(e.getMessage(), e);
                }
              });
      // the download has opened its file and is reading when the loop drops the connection
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (smb.meter().requests() < baseline + 6 && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertThat(download).as("still reading when the loop hits").isNotDone();
      assertThatThrownBy(() -> smb.download("a/x.txt", "x.txt", 1000))
          .isInstanceOf(SmbAccessException.Link.class);

      Path file = download.get(2, TimeUnit.MINUTES);
      try {
        assertThat(Files.size(file)).isEqualTo(200L * 1024 * 1024);
        assertThat(smb.retriedAfterReset.get()).isEqualTo(1);
      } finally {
        Files.deleteIfExists(file);
      }
    }
  }

  private SmbShareClient client() {
    return SmbShareClient.of(
        SmbAddress.parse(samba.url(SambaFixture.LINK_SHARE)),
        SmbCredentials.parse(samba.credentials()),
        TargetAddressValidator.disabled(),
        RequestBudget.unbounded(),
        Duration.ofSeconds(30));
  }
}
