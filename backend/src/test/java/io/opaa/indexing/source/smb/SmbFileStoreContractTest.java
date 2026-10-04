package io.opaa.indexing.source.smb;

import io.opaa.indexing.filesync.ExpiringCheckpoints;
import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.filesync.FileStoreResumptionContract;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The file store contract with resumption against a real Samba ({@link SambaFixture}): each
 * container is a folder of the share, a denied listing is a folder the service account may not
 * enter, a denied read files it may not open, refused credentials a wrong password, and every SMB
 * message counts against a run's budget.
 */
@Testcontainers(disabledWithoutDocker = true)
class SmbFileStoreContractTest extends FileStoreResumptionContract {

  private static final AtomicInteger SCENARIOS = new AtomicInteger();

  @Override
  protected Fixture fixture() {
    SambaFixture samba = SambaFixture.get();
    int scenario = SCENARIOS.incrementAndGet();
    List<String> folders =
        List.of("/Vertrag " + scenario + " eins", "/Vertrag " + scenario + " zwei");
    folders.forEach(folder -> samba.mkdirs(folder.substring(1)));
    return new Fixture() {
      private String credentials = samba.credentials();
      private final ExpiringCheckpoints checkpoints = new ExpiringCheckpoints();

      @Override
      public void put(int container, String name, String text) {
        samba.put(path(container, name), text);
      }

      @Override
      public void put(int container, String name, byte[] bytes, String mediaType) {
        samba.put(path(container, name), bytes);
      }

      @Override
      public void remove(int container, String name) {
        samba.remove(path(container, name));
      }

      @Override
      public void denyListing(int container) {
        samba.denyListing(folders.get(container).substring(1));
      }

      @Override
      public void denyListingOf(int container, String folder) {
        samba.denyListing(path(container, folder));
      }

      @Override
      public void denyReading(int container) {
        samba.denyReading(folders.get(container).substring(1));
      }

      @Override
      public void rejectCredentials() {
        credentials = SambaFixture.DOMAIN + "\\" + SambaFixture.USER + ":kein-gueltiges-passwort";
      }

      @Override
      public String containerKey(int container) {
        return folders.get(container);
      }

      @Override
      public String filePath(int container, String name) {
        return samba.url(SambaFixture.SHARE) + "/" + path(container, name);
      }

      @Override
      public void move(int container, String from, String to) {
        samba.move(path(container, from), path(container, to));
      }

      @Override
      public FileStore open(int pageSize) {
        return checkpoints.wrap(SmbTestStores.open(samba.settings(credentials, folders), pageSize));
      }

      @Override
      public FileStore open(int pageSize, int budget) {
        return checkpoints.wrap(
            SmbTestStores.open(
                samba.settings(credentials, folders),
                pageSize,
                new RequestBudget(new SourceRequestMeter(), budget, null)));
      }

      @Override
      public void expireCheckpoints() {
        checkpoints.expire();
      }

      @Override
      public int minimumBudget() {
        return 40;
      }

      private String path(int container, String name) {
        return folders.get(container).substring(1) + "/" + name;
      }
    };
  }
}
