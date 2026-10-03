package io.opaa.integration.nextcloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.filesync.FileSyncHarness;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.source.nextcloud.NextcloudTestStores;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What a real Nextcloud shows beyond the contract: an unchanged tree costs one request per
 * configured folder, a renamed folder keeps its documents, and a group folder propagates its ETags
 * like a share.
 */
@Testcontainers(disabledWithoutDocker = true)
class NextcloudContainerTest {

  private static final AtomicInteger SCENARIOS = new AtomicInteger();

  private final NextcloudFixture nextcloud = NextcloudFixture.get();
  private final FileSyncHarness harness;
  private FileStore lastStore;

  NextcloudContainerTest() throws Exception {
    harness = new FileSyncHarness();
  }

  private FileSyncHarness.Run fullSync(List<String> folders) {
    lastStore =
        NextcloudTestStores.open(
            NextcloudTestStores.settings(nextcloud.baseUrl(), nextcloud.credentials(), folders));
    return harness.fullSync(lastStore);
  }

  private void put(String path, String text) {
    nextcloud.put(NextcloudFixture.OWNER, path, text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void anUnchangedTreeCostsOneRequestPerConfiguredFolderAndOneForTheSignIn() {
    int scenario = SCENARIOS.incrementAndGet();
    List<String> folders = List.of("/Baum-" + scenario + "-a", "/Baum-" + scenario + "-b");
    put(folders.get(0) + "/x.txt", "X.");
    put(folders.get(0) + "/Akten/2026/y.txt", "Y.");
    put(folders.get(1) + "/Tief/Tiefer/z.txt", "Z.");
    folders.forEach(nextcloud::share);
    assertThat(fullSync(folders).ingested()).hasSize(3);

    FileSyncHarness.Run run = fullSync(folders);

    assertThat(run.listingComplete()).isTrue();
    assertThat(run.processed() + run.skipped()).isZero();
    assertThat(lastStore.meter().requests()).isEqualTo(3);
  }

  @Test
  void aRenamedFolderKeepsItsDocumentsAndTheirFileIds() {
    int scenario = SCENARIOS.incrementAndGet();
    String folder = "/Umbenannt-" + scenario;
    put(folder + "/Alt/a.txt", "A.");
    nextcloud.share(folder);
    fullSync(List.of(folder));
    String filePath =
        nextcloud.baseUrl()
            + "/index.php/f/"
            + nextcloud.fileId(NextcloudFixture.OWNER, folder + "/Alt/a.txt");
    assertThat(harness.storedPaths()).containsExactly(filePath);

    nextcloud.move(NextcloudFixture.OWNER, folder + "/Alt", folder + "/Neu");
    FileSyncHarness.Run run = fullSync(List.of(folder));

    assertThat(run.eventsOf(IndexingEventCategory.REMOVED)).isEmpty();
    assertThat(harness.storedPaths()).containsExactly(filePath);
    assertThat(harness.stored(filePath).orElseThrow().getSourceHierarchyPath()).isEqualTo("Neu");
  }

  @Test
  void aGroupFolderIsListedAndSparedLikeAnyOtherFolder() {
    assumeTrue(nextcloud.groupFoldersAvailable(), "group folders app needs the app store");
    int scenario = SCENARIOS.incrementAndGet();
    String name = "Gruppenablage-" + scenario;
    nextcloud.groupFolder(name);
    put("/" + name + "/Vorlagen/v.txt", "Erste Fassung.");
    List<String> folders = List.of("/" + name);
    assertThat(fullSync(folders).ingested()).hasSize(1);

    put("/" + name + "/Vorlagen/v.txt", "Zweite, längere Fassung.");
    assertThat(fullSync(folders).processed()).isEqualTo(1);

    FileSyncHarness.Run unchanged = fullSync(folders);
    assertThat(unchanged.processed() + unchanged.skipped()).isZero();
    assertThat(lastStore.meter().requests()).isEqualTo(2);
  }
}
