package io.opaa.indexing.source.googledrive;

import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.filesync.FileStoreContract;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.security.TargetAddressValidator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;

/**
 * The file connector contract against the Drive store on the {@link FakeDriveServer}: container
 * {@code 0} is a shared drive (listed flat after its folders), container {@code 1} a folder (listed
 * level by level).
 */
class GoogleDriveFileStoreContractTest extends FileStoreContract {

  private FakeDriveServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.close();
    }
  }

  @Override
  protected Fixture fixture() {
    server = new FakeDriveServer();
    server.addDrive("drive0", "Ablage Null");
    server.folder("folder1", "Ordner Eins", FakeDriveServer.ROOT_ID, null);
    return new DriveFixture(server);
  }

  /** Names map to ids and folders the way the contract spells them. */
  static final class DriveFixture implements Fixture {

    private final FakeDriveServer server;

    DriveFixture(FakeDriveServer server) {
      this.server = server;
    }

    @Override
    public void put(int container, String name, String text) {
      put(container, name, text.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain");
    }

    @Override
    public void put(int container, String name, byte[] bytes, String mediaType) {
      String parent = parentOf(container, name);
      String id = id(container, name);
      FakeDriveServer.Item existing = server.get(id);
      if (existing != null) {
        existing.content = bytes;
        existing.modifiedTime = java.time.Instant.now();
        return;
      }
      server.file(
          id,
          name.substring(name.lastIndexOf('/') + 1),
          mediaType == null ? "application/octet-stream" : mediaType,
          parent,
          container == 0 ? "drive0" : null,
          bytes);
    }

    @Override
    public void remove(int container, String name) {
      server.remove(id(container, name));
    }

    @Override
    public void denyListing(int container) {
      if (container == 0) {
        server.failNext("drives/drive0", 404, "notFound", 100);
      } else {
        server.failNext("files/folder1", 404, "notFound", 100);
      }
    }

    @Override
    public void denyReading(int container) {
      server.failNext("files/" + (container == 0 ? "f0" : "f1"), 403, "forbidden", 100);
    }

    @Override
    public void rejectCredentials() {
      server.rejectToken();
    }

    @Override
    public String containerKey(int container) {
      return container == 0 ? "drive:drive0" : "folder:folder1";
    }

    @Override
    public String filePath(int container, String name) {
      return DriveFileStore.OPEN_PREFIX + id(container, name);
    }

    @Override
    public FileStore open(int pageSize) {
      DriveApiFactory apis =
          new DriveApiFactory(
              GoogleDriveProperties.defaults(), TargetAddressValidator.disabled(), wait -> {});
      SourceSettings settings =
          new SourceSettings(null, server.base().toString(), null, null, false, null);
      GoogleDriveSettings driveSettings =
          GoogleDriveSettings.read(
              ConnectorData.of(
                  Map.of(
                      "scopes", List.of(Map.of("drive", "drive0"), Map.of("folder", "folder1")))));
      return new DriveFileStore(
          apis.open(settings, () -> FakeDriveServer.TOKEN, RequestBudget.unbounded()),
          driveSettings,
          pageSize);
    }

    /** File ids keep the container in their prefix, so a denial can target one container. */
    static String id(int container, String name) {
      return "f" + container + "_" + name.replaceAll("[^A-Za-z0-9]", "_");
    }

    private String parentOf(int container, String name) {
      String parent = container == 0 ? "drive0" : "folder1";
      int slash = name.lastIndexOf('/');
      if (slash < 0) {
        return parent;
      }
      String path = "";
      for (String segment : name.substring(0, slash).split("/")) {
        path = path + "_" + segment;
        String folderId = "d" + container + path.replaceAll("[^A-Za-z0-9_]", "_");
        if (server.get(folderId) == null) {
          server.folder(folderId, segment, parent, container == 0 ? "drive0" : null);
        }
        parent = folderId;
      }
      return parent;
    }
  }
}
