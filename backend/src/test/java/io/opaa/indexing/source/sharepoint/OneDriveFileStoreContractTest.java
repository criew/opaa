package io.opaa.indexing.source.sharepoint;

import io.opaa.indexing.filesync.FileStoreChangeFeedContract;
import io.opaa.msgraph.FakeGraphServer;
import org.junit.jupiter.api.AfterEach;

/**
 * The file connector and change log contracts against the store of OneDrive identities ({@code
 * onedrive://}) on the {@link FakeGraphServer}: two OneDrives for Business, each enumerated and
 * followed through its own delta. A library holds one OneDrive; the second container only lets the
 * contract run its cross-container cases against the shared store.
 */
class OneDriveFileStoreContractTest extends FileStoreChangeFeedContract {

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
    return new SharePointFileStoreContractTest.SharePointFixture(server, DriveRule.ONEDRIVE);
  }
}
