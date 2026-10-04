package io.opaa.indexing.source.smb;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SourceSettings;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * One real Samba per JVM, started lazily and stopped by Ryuk/JVM exit - small enough for the
 * regular {@code test} task. The share {@code daten} is read by the service account {@code opaa}
 * under ordinary file permissions; {@code writer} is an admin user of the share and fills it. The
 * share {@code gesperrt} admits only {@code writer}.
 */
final class SambaFixture {

  private static final Logger log = LoggerFactory.getLogger(SambaFixture.class);

  /** The regex manager in {@code renovate.json5} reads the tag from the comment below. */
  // renovate: datasource=docker depName=dockurr/samba
  static final String IMAGE = "dockurr/samba:4.23.10";

  static final String SHARE = "daten";
  static final String LOCKED_SHARE = "gesperrt";
  static final String LINK_SHARE = "verweise";
  static final String DOMAIN = "OPAA";
  static final String USER = "opaa";
  static final String PASSWORD = "Opaa-Smb-Test-2026!";
  private static final String WRITER = "writer";
  private static final String WRITER_PASSWORD = "Writer-Smb-Test-2026!";
  private static final String ROOT = "/shared/";

  private static final String SMB_CONF =
      """
      [global]
         server role = standalone server
         workgroup = OPAA
         map to guest = never
         server min protocol = SMB2_10
         load printers = no
         disable spoolss = yes
         host msdfs = yes
         follow symlinks = yes
         wide links = no
         log level = 1

      [daten]
         path = /shared/daten
         read only = no
         admin users = writer
         create mask = 0644
         directory mask = 0755
         msdfs root = yes

      [gesperrt]
         path = /shared/gesperrt
         read only = no
         valid users = writer

      [verweise]
         path = /shared/verweise
         read only = yes
         follow symlinks = no
      """;

  private static final String USERS_CONF =
      USER
          + ":1000:smb:1000:"
          + PASSWORD
          + "\n"
          + WRITER
          + ":1001:smb:1000:"
          + WRITER_PASSWORD
          + "\n";

  private static SambaFixture instance;

  private final GenericContainer<?> container;
  private final SMBClient writerClient = new SMBClient();
  private DiskShare writerShare;

  static synchronized SambaFixture get() {
    if (instance == null) {
      SambaFixture fixture = new SambaFixture();
      instance = fixture;
      Runtime.getRuntime().addShutdownHook(new Thread(fixture::stop));
    }
    return instance;
  }

  @SuppressWarnings("resource")
  private SambaFixture() {
    log.info("Starting Samba {}", IMAGE);
    long started = System.nanoTime();
    container =
        new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withCopyToContainer(Transferable.of(SMB_CONF), "/etc/samba/smb.conf")
            .withCopyToContainer(Transferable.of(USERS_CONF), "/etc/samba/users.conf")
            .withExposedPorts(445)
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofMinutes(2));
    container.start();
    exec("mkdir", "-p", ROOT + SHARE, ROOT + LOCKED_SHARE, ROOT + LINK_SHARE + "/echt");
    exec("sh", "-c", "echo Echt > " + ROOT + LINK_SHARE + "/echt/datei.txt");
    exec("chmod", "-R", "a+rX", ROOT + LINK_SHARE);
    // a link loop the server reports as links instead of resolving them itself
    exec("ln", "-s", "b", ROOT + LINK_SHARE + "/a");
    exec("ln", "-s", "a", ROOT + LINK_SHARE + "/b");
    exec("chmod", "0755", ROOT, ROOT + SHARE, ROOT + LOCKED_SHARE);
    log.info("Samba answered after {} ms", (System.nanoTime() - started) / 1_000_000);
  }

  String host() {
    return "127.0.0.1";
  }

  int port() {
    return container.getMappedPort(445);
  }

  /** {@code smb://127.0.0.1:<port>/<share>}. */
  String url(String share) {
    return "smb://" + host() + ":" + port() + "/" + share;
  }

  /** {@code OPAA\opaa:<password>}, what a library stores as its credentials. */
  String credentials() {
    return DOMAIN + "\\" + USER + ":" + PASSWORD;
  }

  SourceSettings settings(String credentials, List<String> folders) {
    return new SourceSettings(
        null, url(SHARE), null, credentials, false, ConnectorData.of(Map.of("folders", folders)));
  }

  /** Creates {@code path} below the share {@code daten} and its parents. */
  void mkdirs(String path) {
    StringBuilder current = new StringBuilder();
    for (String segment : path.split("/")) {
      if (segment.isEmpty()) {
        continue;
      }
      if (!current.isEmpty()) {
        current.append('/');
      }
      current.append(segment);
      if (!share().folderExists(current.toString())) {
        share().mkdir(current.toString());
      }
    }
  }

  void put(String path, String text) {
    put(path, text.getBytes(StandardCharsets.UTF_8));
  }

  /** Writes {@code path} below the share {@code daten}, creating its folders. */
  void put(String path, byte[] bytes) {
    int slash = path.lastIndexOf('/');
    if (slash > 0) {
      mkdirs(path.substring(0, slash));
    }
    try (File file =
            share()
                .openFile(
                    path,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    null,
                    EnumSet.allOf(SMB2ShareAccess.class),
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
        OutputStream out = file.getOutputStream()) {
      out.write(bytes);
    } catch (IOException e) {
      throw new IllegalStateException("writing " + path + " failed", e);
    }
  }

  void remove(String path) {
    share().rm(path);
  }

  /**
   * Renames or moves {@code from} to {@code to} below the share {@code daten}, creating folders.
   */
  void move(String from, String to) {
    int slash = to.lastIndexOf('/');
    if (slash > 0) {
      mkdirs(to.substring(0, slash));
    }
    exec("mv", ROOT + SHARE + "/" + from, ROOT + SHARE + "/" + to);
  }

  /** From now on the service account may neither list nor enter {@code folder}. */
  void denyListing(String folder) {
    exec("chmod", "0000", ROOT + SHARE + "/" + folder);
  }

  /** From now on the service account may read no file below {@code folder}. */
  void denyReading(String folder) {
    exec("find", ROOT + SHARE + "/" + folder, "-type", "f", "-exec", "chmod", "0000", "{}", "+");
  }

  /** A symbolic link at {@code path} (below {@code daten}) pointing to {@code target}. */
  void symlink(String target, String path) {
    exec("ln", "-s", target, ROOT + SHARE + "/" + path);
  }

  /** A file of {@code megabytes} zero bytes at {@code path} below the share {@code verweise}. */
  void bigFileInLinkShare(String path, int megabytes) {
    exec(
        "dd",
        "if=/dev/zero",
        "of=" + ROOT + LINK_SHARE + "/" + path,
        "bs=1M",
        "count=" + megabytes);
    exec("chmod", "a+r", ROOT + LINK_SHARE + "/" + path);
  }

  /** A DFS link at {@code path} (below {@code daten}) to a share on another server. */
  void dfsLink(String path) {
    exec("ln", "-s", "msdfs:fremder-server\\freigabe", ROOT + SHARE + "/" + path);
  }

  /** Sets {@code path}'s modification time back by an hour, its content unchanged. */
  void touchEarlier(String path) {
    exec(
        "touch", "-d", "@" + (System.currentTimeMillis() / 1000 - 3600), ROOT + SHARE + "/" + path);
  }

  private synchronized DiskShare share() {
    if (writerShare == null) {
      try {
        Connection connection = writerClient.connect(host(), port());
        Session session =
            connection.authenticate(
                new AuthenticationContext(WRITER, WRITER_PASSWORD.toCharArray(), DOMAIN));
        writerShare = (DiskShare) session.connectShare(SHARE);
      } catch (IOException e) {
        throw new IllegalStateException("the writer cannot reach Samba", e);
      }
    }
    return writerShare;
  }

  private void exec(String... command) {
    try {
      Container.ExecResult result = container.execInContainer(command);
      if (result.getExitCode() != 0) {
        throw new IllegalStateException(
            String.join(" ", command) + " failed: " + result.getStderr());
      }
    } catch (IOException e) {
      throw new IllegalStateException(String.join(" ", command) + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }

  private void stop() {
    try {
      writerClient.close();
    } finally {
      container.stop();
    }
  }
}
