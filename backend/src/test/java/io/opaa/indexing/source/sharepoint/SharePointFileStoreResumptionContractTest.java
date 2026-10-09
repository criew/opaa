package io.opaa.indexing.source.sharepoint;

import io.opaa.indexing.filesync.FileStoreResumptionContract;
import io.opaa.msgraph.FakeGraphServer;
import org.junit.jupiter.api.AfterEach;

/**
 * The resumption contract for SharePoint against the {@link FakeGraphServer}: a checkpoint is the
 * page token of the delta enumeration, and the change log since the round began proves absence at
 * its end.
 */
class SharePointFileStoreResumptionContractTest extends FileStoreResumptionContract {

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
    return new SharePointFileStoreContractTest.SharePointFixture(server);
  }
}
