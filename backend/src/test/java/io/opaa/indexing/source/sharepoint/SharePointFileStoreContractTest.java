package io.opaa.indexing.source.sharepoint;

import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.filesync.FileStoreChangeFeedContract;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.msgraph.FakeGraphServer;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;

/**
 * The file connector and change log contracts against the SharePoint store on the {@link
 * FakeGraphServer}: two document libraries of one site, each enumerated and followed through its
 * own delta. A file moved into another library is another document, so the contract's move across
 * containers does not apply.
 */
class SharePointFileStoreContractTest extends FileStoreChangeFeedContract {

  private FakeGraphServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.close();
    }
  }

  @Override
  protected Fixture fixture() {
    server = new FakeGraphServer();
    return new SharePointFixture(server);
  }

  /**
   * Two document libraries the way the file store contracts spell them: container {@code 0} is
   * {@code DRIVE_0}, container {@code 1} {@code DRIVE_1}; a name's folders are created on the way,
   * and a file keeps its id when it is moved.
   */
  static final class SharePointFixture implements Fixture {

    private final FakeGraphServer server;
    private final Map<String, String> ids = new HashMap<>();

    SharePointFixture(FakeGraphServer server) {
      this.server = server;
      SharePointTestStores.twoLibraries(server);
    }

    @Override
    public void put(int container, String name, String text) {
      put(container, name, text.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    @Override
    public void put(int container, String name, byte[] bytes, String mediaType) {
      String id = id(container, name);
      FakeGraphServer.Item existing = server.item(id);
      if (existing == null) {
        server.file(
            SharePointTestStores.drive(container),
            id,
            leaf(name),
            parentOf(container, name),
            bytes);
        return;
      }
      if (existing.deleted) {
        server.restore(id);
      }
      server.update(id, bytes);
    }

    @Override
    public void remove(int container, String name) {
      server.delete(id(container, name));
    }

    @Override
    public void changed(int container, String name) {
      String id = id(container, name);
      if (server.item(id) != null) {
        server.touch(id);
      }
    }

    @Override
    public void move(int container, String from, String to) {
      String id = id(container, from);
      server.move(id, parentOf(container, to));
      server.rename(id, leaf(to));
      ids.put(container + "/" + to, id);
    }

    @Override
    public void expireCursors() {
      server.expireDeltaTokens();
    }

    @Override
    public void expireCheckpoints() {
      server.expirePageTokens();
    }

    @Override
    public void denyListing(int container) {
      server.failNext(
          "drives/" + SharePointTestStores.drive(container), 403, "accessDenied", null, 100_000);
    }

    @Override
    public void denyReading(int container) {
      server.failNext("/blob/f" + container + "_", 403, null, null, 100_000);
    }

    @Override
    public void rejectCredentials() {
      server.acceptOnly("eyJ0eXAiOiJKV1QifQ.widerrufen");
    }

    @Override
    public String containerKey(int container) {
      return SharePointLibrary.KEY_PREFIX + SharePointTestStores.drive(container);
    }

    @Override
    public String filePath(int container, String name) {
      return SharePointFileStore.filePath(
          SharePointTestStores.drive(container), id(container, name));
    }

    @Override
    public FileStore open(int pageSize) {
      return SharePointTestStores.store(
          server, SharePointTestStores.bothLibraries(), pageSize, RequestBudget.unbounded());
    }

    @Override
    public FileStore open(int pageSize, int budget) {
      return SharePointTestStores.store(
          server,
          SharePointTestStores.bothLibraries(),
          pageSize,
          new RequestBudget(new SourceRequestMeter(), budget, null));
    }

    @Override
    public int minimumBudget() {
      return 6;
    }

    @Override
    public boolean keepsIdentityAcrossContainers() {
      return false;
    }

    /** Item ids keep the container in their prefix, so a denial can target one container. */
    String id(int container, String name) {
      return ids.computeIfAbsent(
          container + "/" + name,
          key -> "f" + container + "_" + name.replaceAll("[^A-Za-z0-9]", "_"));
    }

    private String parentOf(int container, String name) {
      String parent = FakeGraphServer.rootId(SharePointTestStores.drive(container));
      int slash = name.lastIndexOf('/');
      if (slash < 0) {
        return parent;
      }
      String path = "";
      for (String segment : name.substring(0, slash).split("/")) {
        path = path + "_" + segment;
        String folderId = "d" + container + path.replaceAll("[^A-Za-z0-9_]", "_");
        if (server.item(folderId) == null) {
          server.folder(SharePointTestStores.drive(container), folderId, segment, parent);
        }
        parent = folderId;
      }
      return parent;
    }

    private static String leaf(String name) {
      return name.substring(name.lastIndexOf('/') + 1);
    }
  }
}
