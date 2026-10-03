package io.opaa.indexing.source.smb;

import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.filesync.FileStoreContract;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The file store contract against a real Samba ({@link SambaFixture}): each container is a folder
 * of the share, a denied listing is a folder the service account may not enter, a denied read files
 * it may not open, refused credentials a wrong password.
 */
@Testcontainers(disabledWithoutDocker = true)
class SmbFileStoreContractTest extends FileStoreContract {

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
      public FileStore open(int pageSize) {
        return SmbTestStores.open(samba.settings(credentials, folders), pageSize);
      }

      private String path(int container, String name) {
        return folders.get(container).substring(1) + "/" + name;
      }
    };
  }
}
