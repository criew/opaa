package io.opaa.integration.nextcloud;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * One real Nextcloud per JVM for {@code ./gradlew nextcloudIntegrationTest}, started lazily and
 * stopped by Ryuk/JVM exit: SQLite, the technical user {@code opaa} with an app password, and the
 * owner {@code alice}, whose folders reach {@code opaa} as shares. The group folders app comes from
 * the app store; without internet it stays absent and its test is skipped.
 */
final class NextcloudFixture {

  private static final Logger log = LoggerFactory.getLogger(NextcloudFixture.class);

  /** The regex manager in {@code renovate.json5} reads the tag from the comment below. */
  // renovate: datasource=docker depName=nextcloud
  static final String IMAGE = "nextcloud:35.0.1-apache";

  static final String TECH_USER = "opaa";
  static final String OWNER = "alice";
  private static final String PASSWORD = "Nextcloud-Test-2026!";
  private static final String ADMIN = "admin";

  private static NextcloudFixture instance;

  private final GenericContainer<?> container;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final String appPassword;
  private final boolean groupFolders;

  static synchronized NextcloudFixture get() {
    if (instance == null) {
      NextcloudFixture fixture = new NextcloudFixture();
      instance = fixture;
      Runtime.getRuntime().addShutdownHook(new Thread(fixture.container::stop));
    }
    return instance;
  }

  @SuppressWarnings("resource")
  private NextcloudFixture() {
    log.info("Starting Nextcloud {}", IMAGE);
    container =
        new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withEnv("SQLITE_DATABASE", "nextcloud")
            .withEnv("NEXTCLOUD_ADMIN_USER", ADMIN)
            .withEnv("NEXTCLOUD_ADMIN_PASSWORD", PASSWORD)
            .withEnv("NEXTCLOUD_TRUSTED_DOMAINS", "localhost 127.0.0.1")
            .withExposedPorts(80)
            .waitingFor(
                Wait.forHttp("/status.php")
                    .forPort(80)
                    .forResponsePredicate(body -> body.contains("\"installed\":true")))
            .withStartupTimeout(Duration.ofMinutes(5));
    container.start();
    // the contract refuses credentials on purpose; throttling would slow every later scenario
    occ(
        "config:system:set",
        "auth.bruteforce.protection.enabled",
        "--value=false",
        "--type=boolean");
    // every scenario shares new folders; the sharing API's own rate limit would refuse them
    occ("config:system:set", "ratelimit.protection.enabled", "--value=false", "--type=boolean");
    occ("user:add", "--password-from-env", TECH_USER);
    occ("user:add", "--password-from-env", OWNER);
    appPassword = lastLine(occ("user:auth-tokens:add", "--password-from-env", TECH_USER));
    groupFolders = installGroupFolders();
  }

  /**
   * The address the store reaches the instance under - loopback, as the suite needs local Docker.
   */
  String baseUrl() {
    return "http://127.0.0.1:" + container.getMappedPort(80);
  }

  /** {@code opaa:<app password>}, what a library stores as its credentials. */
  String credentials() {
    return TECH_USER + ":" + appPassword;
  }

  /**
   * A further Nextcloud user {@code user} with a new app password of her own, as a person connects
   * it; {@code <app password>}.
   */
  synchronized String appPasswordOf(String user) {
    occ("user:add", "--password-from-env", user);
    return lastLine(occ("user:auth-tokens:add", "--password-from-env", user));
  }

  /**
   * Revokes every app password of {@code user} through her own settings, as she would; through the
   * web server, so its token cache forgets them too, which {@code occ} would not reach.
   */
  synchronized void revokeAppPasswords(String user) {
    String listed = occ("user:auth-tokens:list", "--output=json", user);
    Matcher ids = Pattern.compile("\"id\":\\s*\"?(\\d+)").matcher(listed);
    int revoked = 0;
    while (ids.find()) {
      HttpRequest request =
          authorized(
                  user,
                  URI.create(baseUrl() + "/index.php/settings/personal/authtokens/" + ids.group(1)))
              .header("OCS-APIRequest", "true")
              .DELETE()
              .build();
      require(send(request), 200);
      revoked++;
    }
    if (revoked == 0) {
      throw new IllegalStateException("no app password of " + user + " listed: " + listed);
    }
  }

  boolean groupFoldersAvailable() {
    return groupFolders;
  }

  /** Creates {@code path} (and its parents) in {@code user}'s files. */
  void mkdirs(String user, String path) {
    StringBuilder current = new StringBuilder();
    for (String segment : path.split("/")) {
      if (segment.isEmpty()) {
        continue;
      }
      current.append('/').append(segment);
      int status = dav(user, "MKCOL", current.toString(), null).statusCode();
      if (status != 201 && status != 405) {
        throw new IllegalStateException("MKCOL " + current + " answered " + status);
      }
    }
  }

  void put(String user, String path, byte[] bytes) {
    int slash = path.lastIndexOf('/');
    if (slash > 0) {
      mkdirs(user, path.substring(0, slash));
    }
    require(dav(user, "PUT", path, bytes), 201, 204);
  }

  void delete(String user, String path) {
    require(dav(user, "DELETE", path, null), 204, 404);
  }

  void move(String user, String from, String to) {
    HttpRequest request =
        authorized(user, URI.create(davUrl(user, from)))
            .header("Destination", davUrl(user, to))
            .method("MOVE", HttpRequest.BodyPublishers.noBody())
            .build();
    require(send(request), 201, 204);
  }

  /** The {@code oc:fileid} of {@code path} in {@code user}'s files. */
  String fileId(String user, String path) {
    HttpRequest request =
        authorized(user, URI.create(davUrl(user, path)))
            .header("Depth", "0")
            .method(
                "PROPFIND",
                HttpRequest.BodyPublishers.ofString(
                    "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\""
                        + " xmlns:oc=\"http://owncloud.org/ns\"><d:prop><oc:fileid/></d:prop>"
                        + "</d:propfind>"))
            .build();
    HttpResponse<String> response = send(request);
    Matcher matcher = Pattern.compile("<oc:fileid>(\\d+)</oc:fileid>").matcher(response.body());
    if (!matcher.find()) {
      throw new IllegalStateException("no file id for " + path + ": " + response.body());
    }
    return matcher.group(1);
  }

  /** Shares {@code OWNER}'s folder {@code path} read-only with the technical user; the share id. */
  String share(String path) {
    HttpRequest request =
        authorized(OWNER, URI.create(baseUrl() + "/ocs/v2.php/apps/files_sharing/api/v1/shares"))
            .header("OCS-APIRequest", "true")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"path\":\""
                        + path
                        + "\",\"shareType\":0,\"shareWith\":\""
                        + TECH_USER
                        + "\",\"permissions\":1}"))
            .build();
    HttpResponse<String> response = send(request);
    Matcher matcher = Pattern.compile("\"id\":\"?(\\d+)").matcher(response.body());
    if (response.statusCode() != 200 || !matcher.find()) {
      throw new IllegalStateException("sharing " + path + " failed: " + response.body());
    }
    return matcher.group(1);
  }

  void unshare(String shareId) {
    HttpRequest request =
        authorized(
                OWNER,
                URI.create(baseUrl() + "/ocs/v2.php/apps/files_sharing/api/v1/shares/" + shareId))
            .header("OCS-APIRequest", "true")
            .DELETE()
            .build();
    require(send(request), 200);
  }

  /** Makes every file below {@code OWNER}'s folder {@code path} unreadable on disk. */
  void denyReading(String path) {
    exec(
        "sh",
        "-c",
        "find '/var/www/html/data/"
            + OWNER
            + "/files"
            + path
            + "' -type f "
            + "-exec chmod 000 {} +");
  }

  /** A group folder named {@code name} the technical user can read and the owner can write. */
  void groupFolder(String name) {
    String id = lastLine(occ("groupfolders:create", name));
    occ("group:add", "referat");
    occ("group:adduser", "referat", TECH_USER);
    occ("group:adduser", "referat", OWNER);
    occ("groupfolders:group", id, "referat", "read", "write");
  }

  private boolean installGroupFolders() {
    try {
      Container.ExecResult result =
          container.execInContainer(
              "su", "-s", "/bin/sh", "www-data", "-c", "php occ app:install groupfolders");
      boolean installed =
          result.getExitCode() == 0 || result.getStdout().contains("already installed");
      if (!installed) {
        log.warn("Group folders app not installable: {}", result.getStdout() + result.getStderr());
      }
      return installed;
    } catch (IOException | InterruptedException e) {
      log.warn("Group folders app not installable", e);
      return false;
    }
  }

  private String occ(String... arguments) {
    StringBuilder command = new StringBuilder("php occ");
    for (String argument : arguments) {
      command.append(' ').append('\'').append(argument).append('\'');
    }
    return exec(
        "su",
        "-s",
        "/bin/sh",
        "www-data",
        "-c",
        "OC_PASS='" + PASSWORD + "' NC_PASS='" + PASSWORD + "' " + command);
  }

  private String exec(String... command) {
    try {
      Container.ExecResult result = container.execInContainer(command);
      if (result.getExitCode() != 0
          && !result.getStderr().contains("already exists")
          && !result.getStdout().contains("already exists")) {
        throw new IllegalStateException(
            String.join(" ", command) + " failed: " + result.getStdout() + result.getStderr());
      }
      return result.getStdout();
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException("exec failed: " + String.join(" ", command), e);
    }
  }

  private HttpResponse<String> dav(String user, String method, String path, byte[] body) {
    HttpRequest request =
        authorized(user, URI.create(davUrl(user, path)))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    return send(request);
  }

  private String davUrl(String user, String path) {
    StringBuilder url = new StringBuilder(baseUrl() + "/remote.php/dav/files/" + user);
    for (String segment : path.split("/")) {
      if (!segment.isEmpty()) {
        url.append('/')
            .append(
                java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
      }
    }
    return url.toString();
  }

  private HttpRequest.Builder authorized(String user, URI uri) {
    String token =
        Base64.getEncoder()
            .encodeToString((user + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    return HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Basic " + token);
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException("Nextcloud call failed: " + request.uri(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }

  private static void require(HttpResponse<String> response, int... expected) {
    for (int status : expected) {
      if (response.statusCode() == status) {
        return;
      }
    }
    throw new IllegalStateException(
        response.request().method()
            + " "
            + response.request().uri()
            + " answered "
            + response.statusCode()
            + ": "
            + response.body());
  }

  private static String lastLine(String output) {
    String[] lines = output.strip().split("\\R");
    return lines[lines.length - 1].strip();
  }
}
