package io.opaa.indexing.source.nextcloud;

import io.opaa.indexing.filesync.FileStoreResumptionContract;
import org.junit.jupiter.api.AfterEach;

/**
 * The resumption contract for Nextcloud against {@link FakeNextcloudServer}: every request of a run
 * counts against its budget, a checkpoint is the path of a folder in the depth-first walk.
 */
class NextcloudFileStoreResumptionContractTest extends FileStoreResumptionContract {

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
    return NextcloudFileStoreContractTest.fixtureOver(server);
  }
}
