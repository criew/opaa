package io.opaa.indexing.source.dropbox;

import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.filesync.FileStoreChangeFeedContract;
import io.opaa.indexing.spi.ConnectorData;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;

/**
 * The file connector and change log contracts against the Dropbox store under a team app: container
 * {@code 0} is the team folder {@code 100} („Bauamt“), container {@code 1} the team folder {@code
 * 200} („Kämmerei“), each read from its namespace root as the team admin.
 */
class DropboxTeamFolderStoreContractTest extends FileStoreChangeFeedContract {

  private static final List<String> NAMESPACES = List.of("100", "200");
  private static final List<String> NAMES = List.of("Bauamt", "Kämmerei");

  private FakeDropboxServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.close();
    }
  }

  @Override
  protected Fixture fixture() {
    server = new FakeDropboxServer();
    server.withTeamApp();
    for (int i = 0; i < NAMESPACES.size(); i++) {
      server.teamFolder(NAMESPACES.get(i), NAMES.get(i));
    }
    return new TeamFolderFixture(server);
  }

  /** Names lie directly below the namespace root of the container's team folder. */
  private static final class TeamFolderFixture implements Fixture {

    private final FakeDropboxServer server;

    TeamFolderFixture(FakeDropboxServer server) {
      this.server = server;
    }

    @Override
    public void put(int container, String name, String text) {
      put(container, name, text.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    @Override
    public void put(int container, String name, byte[] bytes, String mediaType) {
      server.file(NAMESPACES.get(container), "/" + name, bytes);
    }

    @Override
    public void remove(int container, String name) {
      server.remove(NAMESPACES.get(container), "/" + name);
    }

    @Override
    public void changed(int container, String name) {
      server.changed(NAMESPACES.get(container), "/" + name);
    }

    @Override
    public boolean keepsIdentityAcrossContainers() {
      return false;
    }

    @Override
    public void expireCursors() {
      server.expireCursors();
    }

    @Override
    public void denyListing(int container) {
      server.denyListing(NAMESPACES.get(container), "");
    }

    @Override
    public void denyReading(int container) {
      server.denyReading(NAMESPACES.get(container), "");
    }

    @Override
    public void rejectCredentials() {
      server.rejectToken();
    }

    @Override
    public String containerKey(int container) {
      return NAMESPACES.get(container);
    }

    @Override
    public String filePath(int container, String name) {
      return DropboxFileStore.IDENTITY_PREFIX
          + "ns:"
          + NAMESPACES.get(container)
          + "/"
          + name.toLowerCase(Locale.ROOT);
    }

    @Override
    public FileStore open(int pageSize) {
      return new DropboxFileStore(
          DropboxFileStoreContractTest.api(server, FakeDropboxServer.TOKEN),
          DropboxSettings.read(
              ConnectorData.of(
                  Map.of(
                      "teamApp",
                      true,
                      "teamFolders",
                      List.of(
                          Map.of("id", NAMESPACES.get(0), "name", NAMES.get(0)),
                          Map.of("id", NAMESPACES.get(1), "name", NAMES.get(1)))))),
          pageSize);
    }
  }
}
