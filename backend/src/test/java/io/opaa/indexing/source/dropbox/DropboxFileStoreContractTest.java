package io.opaa.indexing.source.dropbox;

import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.filesync.FileStoreChangeFeedContract;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.security.TargetAddressValidator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;

/**
 * The file connector and change log contracts against the Dropbox store on the {@link
 * FakeDropboxServer}: container {@code 0} is the folder {@code /Akten}, container {@code 1} the
 * folder {@code /Projekte Eins}, each listed recursively with its own change stream.
 */
class DropboxFileStoreContractTest extends FileStoreChangeFeedContract {

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
    server.folder("/Akten");
    server.folder("/Projekte Eins");
    return new DropboxFixture(server);
  }

  /** Names lie below the container's folder the way the contract spells them. */
  static final class DropboxFixture implements Fixture {

    static final List<String> FOLDERS = List.of("/Akten", "/Projekte Eins");

    private final FakeDropboxServer server;

    DropboxFixture(FakeDropboxServer server) {
      this.server = server;
    }

    @Override
    public void put(int container, String name, String text) {
      put(container, name, text.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    @Override
    public void put(int container, String name, byte[] bytes, String mediaType) {
      server.file(FakeDropboxServer.HOME, path(container, name), bytes);
    }

    @Override
    public void remove(int container, String name) {
      server.remove(path(container, name));
    }

    @Override
    public void changed(int container, String name) {
      server.changed(path(container, name));
    }

    @Override
    public boolean keepsIdentityAcrossContainers() {
      return false;
    }

    @Override
    public boolean marksUnreachableStartCursor() {
      return true;
    }

    @Override
    public void expireCursors() {
      server.expireCursors();
    }

    @Override
    public void denyListing(int container) {
      server.denyListing(FOLDERS.get(container));
    }

    @Override
    public void denyReading(int container) {
      server.denyReading(FOLDERS.get(container));
    }

    @Override
    public void rejectCredentials() {
      server.rejectToken();
    }

    @Override
    public String containerKey(int container) {
      return FOLDERS.get(container);
    }

    @Override
    public String filePath(int container, String name) {
      return DropboxFileStore.IDENTITY_PREFIX + path(container, name).toLowerCase(Locale.ROOT);
    }

    @Override
    public FileStore open(int pageSize) {
      return new DropboxFileStore(
          api(server, FakeDropboxServer.TOKEN),
          DropboxSettings.read(ConnectorData.of(Map.of("folders", FOLDERS))),
          pageSize);
    }

    static String path(int container, String name) {
      return FOLDERS.get(container) + "/" + name;
    }
  }

  /** An API on the fake with a fixed token and no retry wait. */
  static DropboxApi api(FakeDropboxServer server, String token) {
    DropboxApiFactory apis =
        new DropboxApiFactory(
            DropboxProperties.at(server.base()), TargetAddressValidator.disabled(), wait -> {});
    return apis.open(
        new SourceSettings(null, server.base().toString(), null, null, false, null),
        () -> token,
        RequestBudget.unbounded());
  }
}
