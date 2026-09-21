package io.opaa.integration.keycloak;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One real Keycloak per JVM for {@code ./gradlew keycloakIntegrationTest}, started lazily on first
 * use and stopped by Ryuk/JVM exit. Seeds one realm with a group tree, users and the service
 * account {@code opaa-directory} that holds exactly the two realm-management roles ADR-0036,
 * Entscheidung 3 names: {@code view-users} and {@code query-groups}.
 *
 * <p>Deliberately a plain {@link GenericContainer} rather than a third-party Keycloak module: the
 * three endpoints this suite needs are HTTP, and one more dependency to keep in step with the
 * Keycloak line buys nothing.
 *
 * <p>The seeding goes through the Admin API with the bootstrap administrator, so the group ids the
 * tests assert on are the ones Keycloak minted - never ids this fixture chose.
 */
final class KeycloakFixture {

  private static final Logger log = LoggerFactory.getLogger(KeycloakFixture.class);

  /**
   * Pinned to the tag the Compose stack runs ({@code docker-compose.yml}); the regex manager in
   * {@code renovate.json5} reads it from the comment below.
   */
  // renovate: datasource=docker depName=quay.io/keycloak/keycloak
  static final String IMAGE = "quay.io/keycloak/keycloak:26.7";

  static final String REALM = "opaa-it";
  static final String DIRECTORY_CLIENT_ID = "opaa-directory";
  static final String DIRECTORY_CLIENT_SECRET = "geheim-im-test";

  /** A second service account, deliberately without the two realm-management roles. */
  static final String POWERLESS_CLIENT_ID = "opaa-ohne-rechte";

  static final String POWERLESS_CLIENT_SECRET = "auch-geheim";

  private static KeycloakFixture instance;

  private final GenericContainer<?> container;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final JsonMapper json = JsonMapper.builder().build();

  private String hausGroupId;
  private String referat50GroupId;
  private String referat51GroupId;
  private String externGroupId;
  private final List<String> paginationGroupIds = new ArrayList<>();
  private String user1Id;
  private String user2Id;
  private String user3Id;

  static synchronized KeycloakFixture get() {
    if (instance == null) {
      KeycloakFixture fixture = new KeycloakFixture();
      instance = fixture;
      Runtime.getRuntime().addShutdownHook(new Thread(fixture::stop));
    }
    return instance;
  }

  @SuppressWarnings("resource")
  private KeycloakFixture() {
    log.info("Starting Keycloak {}", IMAGE);
    container =
        new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withCommand("start-dev")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withExposedPorts(8080)
            .waitingFor(
                Wait.forHttp("/realms/master/.well-known/openid-configuration")
                    .forPort(8080)
                    .forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(5));
    container.start();
    seed();
  }

  private void stop() {
    container.stop();
  }

  /**
   * The address the backend reaches this Keycloak under. Loopback on purpose: it is the host the
   * shared test signature allowlists for the sign-in's address policy, and Testcontainers publishes
   * the mapped port on the docker host, so this suite needs a local docker daemon.
   */
  String baseUrl() {
    return "http://127.0.0.1:" + container.getMappedPort(8080);
  }

  String issuerUri() {
    return baseUrl() + "/realms/" + REALM;
  }

  String hausGroupId() {
    return hausGroupId;
  }

  String referat50GroupId() {
    return referat50GroupId;
  }

  String referat51GroupId() {
    return referat51GroupId;
  }

  String externGroupId() {
    return externGroupId;
  }

  List<String> paginationGroupIds() {
    return List.copyOf(paginationGroupIds);
  }

  /** The two members of {@code /Haus/Referat 50}; their Keycloak ids are their token subjects. */
  String user1Id() {
    return user1Id;
  }

  String user2Id() {
    return user2Id;
  }

  /** The member of {@code /Haus/Referat 51}. */
  String user3Id() {
    return user3Id;
  }

  /** Renames a group in the directory; its id - what OPAA matches on - stays the same. */
  void renameGroup(String groupId, String newName) {
    adminPut(
        "/admin/realms/" + REALM + "/groups/" + groupId,
        "{\"id\":\"" + groupId + "\",\"name\":\"" + newName + "\"}");
  }

  // ------------------------------------------------------------------------------------ seeding

  private void seed() {
    adminPost("/admin/realms", "{\"realm\":\"" + REALM + "\",\"enabled\":true}");
    hausGroupId = createGroup(null, "Haus");
    referat50GroupId = createGroup(hausGroupId, "Referat 50");
    referat51GroupId = createGroup(hausGroupId, "Referat 51");
    externGroupId = createGroup(null, "Extern");
    // Enough top-level groups that a page size below their number is demonstrably walked.
    for (int i = 0; i < 8; i++) {
      paginationGroupIds.add(createGroup(null, "Sammelstelle " + i));
    }
    user1Id = createUser("anna.beispiel");
    user2Id = createUser("bernd.beispiel");
    user3Id = createUser("carla.beispiel");
    join(user1Id, referat50GroupId);
    join(user2Id, referat50GroupId);
    join(user3Id, referat51GroupId);
    createDirectoryClient(DIRECTORY_CLIENT_ID, DIRECTORY_CLIENT_SECRET, true);
    createDirectoryClient(POWERLESS_CLIENT_ID, POWERLESS_CLIENT_SECRET, false);
  }

  private String createGroup(String parentId, String name) {
    String path =
        parentId == null
            ? "/admin/realms/" + REALM + "/groups"
            : "/admin/realms/" + REALM + "/groups/" + parentId + "/children";
    return lastPathSegment(adminPost(path, "{\"name\":\"" + name + "\"}"));
  }

  private String createUser(String username) {
    return lastPathSegment(
        adminPost(
            "/admin/realms/" + REALM + "/users",
            "{\"username\":\""
                + username
                + "\",\"enabled\":true,\"email\":\""
                + username
                + "@example.org\",\"firstName\":\"Test\",\"lastName\":\"Person\"}"));
  }

  private void join(String userId, String groupId) {
    adminPut("/admin/realms/" + REALM + "/users/" + userId + "/groups/" + groupId, null);
  }

  /**
   * A confidential client with a service account. With {@code withRights}, exactly the two roles
   * ADR-0036 names; without, none at all - what a misconfigured deployment looks like.
   */
  private void createDirectoryClient(String clientId, String secret, boolean withRights) {
    String location =
        adminPost(
            "/admin/realms/" + REALM + "/clients",
            "{\"clientId\":\""
                + clientId
                + "\",\"enabled\":true,\"publicClient\":false,\"serviceAccountsEnabled\":true,"
                + "\"standardFlowEnabled\":false,\"directAccessGrantsEnabled\":false,\"secret\":\""
                + secret
                + "\"}");
    if (!withRights) {
      return;
    }
    String clientUuid = lastPathSegment(location);
    JsonNode serviceAccount =
        adminGet("/admin/realms/" + REALM + "/clients/" + clientUuid + "/service-account-user");
    JsonNode realmManagement =
        adminGet("/admin/realms/" + REALM + "/clients?clientId=realm-management").get(0);
    JsonNode roles =
        adminGet(
            "/admin/realms/"
                + REALM
                + "/clients/"
                + realmManagement.path("id").asString()
                + "/roles");
    StringBuilder wanted = new StringBuilder("[");
    for (JsonNode role : roles) {
      String name = role.path("name").asString();
      if (!name.equals("view-users") && !name.equals("query-groups")) {
        continue;
      }
      if (wanted.length() > 1) {
        wanted.append(',');
      }
      wanted
          .append("{\"id\":\"")
          .append(role.path("id").asString())
          .append("\",\"name\":\"")
          .append(name)
          .append("\"}");
    }
    adminPost(
        "/admin/realms/"
            + REALM
            + "/users/"
            + serviceAccount.path("id").asString()
            + "/role-mappings/clients/"
            + realmManagement.path("id").asString(),
        wanted.append(']').toString());
  }

  // -------------------------------------------------------------------------------- admin calls

  private String adminToken() {
    String form =
        "grant_type=password&client_id=admin-cli&username=admin&password="
            + URLEncoder.encode("admin", StandardCharsets.UTF_8);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/realms/master/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
            .build();
    return json.readTree(send(request, 200)).path("access_token").asString();
  }

  private String adminPost(String path, String body) {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Authorization", "Bearer " + adminToken())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    return sendForHeader(request);
  }

  private void adminPut(String path, String body) {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Authorization", "Bearer " + adminToken())
            .header("Content-Type", "application/json")
            .PUT(
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    send(request, -1);
  }

  private JsonNode adminGet(String path) {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Authorization", "Bearer " + adminToken())
            .header("Accept", "application/json")
            .GET()
            .build();
    return json.readTree(send(request, 200));
  }

  private String send(HttpRequest request, int expectedStatus) {
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (expectedStatus > 0
          ? response.statusCode() != expectedStatus
          : response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Keycloak answered "
                + response.statusCode()
                + " for "
                + request.uri()
                + ": "
                + response.body());
      }
      return response.body();
    } catch (IOException e) {
      throw new IllegalStateException("Keycloak call failed: " + request.uri(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }

  private String sendForHeader(HttpRequest request) {
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Keycloak answered "
                + response.statusCode()
                + " for "
                + request.uri()
                + ": "
                + response.body());
      }
      return response.headers().firstValue("Location").orElse("");
    } catch (IOException e) {
      throw new IllegalStateException("Keycloak call failed: " + request.uri(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }

  private static String lastPathSegment(String location) {
    return location.substring(location.lastIndexOf('/') + 1);
  }
}
