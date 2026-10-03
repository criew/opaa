package io.opaa.indexing.source.nextcloud;

import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.filesync.FileStoreFolderContract;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;

/**
 * The file store contract for Nextcloud against {@link FakeNextcloudServer} under a context path:
 * two folders as containers, a denied listing or read answers {@code 403}, refused credentials
 * {@code 401}. The same contract runs against a real Nextcloud in {@code nextcloudIntegrationTest}.
 */
class NextcloudFileStoreContractTest extends FileStoreFolderContract {

  private static final List<String> FOLDERS = List.of("/Vertrag Eins", "/Vertrag Zwei");

  private FakeNextcloudServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.close();
    }
  }

  @Override
  protected Fixture fixture() throws Exception {
    server = new FakeNextcloudServer("/nextcloud");
    for (String folder : FOLDERS) {
      server.mkdir(folder.substring(1));
    }
    return new Fixture() {
      /** File ids by path, kept after a removal so a removed file can still be named. */
      private final Map<String, Long> fileIds = new HashMap<>();

      @Override
      public void put(int container, String name, String text) {
        server.put(path(container, name), text);
        fileIds.put(path(container, name), server.fileId(path(container, name)));
      }

      @Override
      public void put(int container, String name, byte[] bytes, String mediaType) {
        server.put(path(container, name), bytes, mediaType);
        fileIds.put(path(container, name), server.fileId(path(container, name)));
      }

      @Override
      public void remove(int container, String name) {
        server.remove(path(container, name));
      }

      @Override
      public void move(int container, String from, String to) {
        server.move(path(container, from), path(container, to));
        NextcloudTestStores.renameIds(fileIds, path(container, from), path(container, to));
      }

      @Override
      public void denyListing(int container) {
        server.denyListing(FOLDERS.get(container).substring(1));
      }

      @Override
      public void denyReading(int container) {
        server.denyReading(FOLDERS.get(container).substring(1));
      }

      @Override
      public void rejectCredentials() {
        server.rejectCredentials();
      }

      @Override
      public String containerKey(int container) {
        return FOLDERS.get(container);
      }

      @Override
      public String filePath(int container, String name) {
        return server.baseUrl() + "/index.php/f/" + fileIds.get(path(container, name));
      }

      @Override
      public FileStore open(int pageSize) {
        return NextcloudTestStores.open(
            NextcloudTestStores.settings(server.baseUrl(), server.credentials(), FOLDERS));
      }

      private String path(int container, String name) {
        return FOLDERS.get(container).substring(1) + "/" + name;
      }
    };
  }
}
