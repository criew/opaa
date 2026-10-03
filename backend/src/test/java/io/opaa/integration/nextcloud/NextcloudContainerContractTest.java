package io.opaa.integration.nextcloud;

import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.filesync.FileStoreFolderContract;
import io.opaa.indexing.source.nextcloud.NextcloudTestStores;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The file store contract against a real Nextcloud ({@link NextcloudFixture}): each container is a
 * folder of {@code alice} shared with the technical user, so the ETag propagation into a share is
 * part of every scenario. A denied listing is a withdrawn share, a denied read a file the instance
 * cannot open, refused credentials a wrong app password.
 */
@Testcontainers(disabledWithoutDocker = true)
class NextcloudContainerContractTest extends FileStoreFolderContract {

  private static final AtomicInteger SCENARIOS = new AtomicInteger();

  @Override
  protected Fixture fixture() {
    NextcloudFixture nextcloud = NextcloudFixture.get();
    int scenario = SCENARIOS.incrementAndGet();
    List<String> folders =
        List.of("/Vertrag-" + scenario + "-eins", "/Vertrag-" + scenario + "-zwei");
    List<String> shares =
        folders.stream()
            .map(
                folder -> {
                  nextcloud.mkdirs(NextcloudFixture.OWNER, folder);
                  return nextcloud.share(folder);
                })
            .toList();
    return new Fixture() {
      private String credentials = nextcloud.credentials();

      /** File ids by path, kept after a removal so a removed file can still be named. */
      private final Map<String, String> fileIds = new HashMap<>();

      @Override
      public void put(int container, String name, String text) {
        put(container, name, text.getBytes(StandardCharsets.UTF_8), "text/plain");
      }

      @Override
      public void put(int container, String name, byte[] bytes, String mediaType) {
        String path = path(container, name);
        nextcloud.put(NextcloudFixture.OWNER, path, bytes);
        fileIds.put(path, nextcloud.fileId(NextcloudFixture.OWNER, path));
      }

      @Override
      public void remove(int container, String name) {
        nextcloud.delete(NextcloudFixture.OWNER, path(container, name));
      }

      @Override
      public void move(int container, String from, String to) {
        nextcloud.move(NextcloudFixture.OWNER, path(container, from), path(container, to));
        NextcloudTestStores.renameIds(fileIds, path(container, from), path(container, to));
      }

      @Override
      public void denyListing(int container) {
        nextcloud.unshare(shares.get(container));
      }

      @Override
      public void denyReading(int container) {
        nextcloud.denyReading(folders.get(container));
      }

      @Override
      public void rejectCredentials() {
        credentials = NextcloudFixture.TECH_USER + ":kein-gueltiges-app-passwort";
      }

      @Override
      public String containerKey(int container) {
        return folders.get(container);
      }

      @Override
      public String filePath(int container, String name) {
        return nextcloud.baseUrl() + "/index.php/f/" + fileIds.get(path(container, name));
      }

      @Override
      public FileStore open(int pageSize) {
        return NextcloudTestStores.open(
            NextcloudTestStores.settings(nextcloud.baseUrl(), credentials, folders));
      }

      private String path(int container, String name) {
        return folders.get(container) + "/" + name;
      }
    };
  }
}
