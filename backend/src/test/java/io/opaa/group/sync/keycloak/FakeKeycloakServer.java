package io.opaa.group.sync.keycloak;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local HTTP double of the Keycloak Admin REST API - the fast counterpart of {@code
 * io.opaa.integration.keycloak}'s real container (ADR-0023's split between a contract-level double
 * and a container-level acceptance suite, applied to #1817).
 *
 * <p>It answers the six calls {@link KeycloakAdminApi} makes and reproduces the two shapes of the
 * real API that the connector depends on and that a hand-written stub gets wrong most easily:
 * {@code /groups} returns <b>top-level groups only</b> with a {@code subGroupCount} and an empty
 * {@code subGroups} array, and {@code /members} returns <b>direct</b> members only. Both are
 * re-verified against a real Keycloak by {@code KeycloakDirectoryConnectorRealmTest}; this double
 * exists for the cases that are tedious there - deep pagination, a rejected sign-in, a malformed
 * answer.
 */
public final class FakeKeycloakServer implements AutoCloseable {

  public static final String REALM = "haus";
  public static final String CLIENT_ID = "opaa-directory";
  public static final String CLIENT_SECRET = "s3cret";

  private final HttpServer server;
  private final Map<String, Group> groups = new LinkedHashMap<>();
  private final Map<String, Boolean> accounts = new LinkedHashMap<>();
  private final AtomicInteger tokenRequests = new AtomicInteger();
  // The HttpServer answers on its own threads; a test reads this list from the test thread.
  private final List<String> requestedPaths = Collections.synchronizedList(new ArrayList<>());

  /** {@code null} means "answer normally"; otherwise every admin call answers with this status. */
  private Integer adminStatus;

  private String expectedSecret = CLIENT_SECRET;

  private long tokenLifetimeSeconds = 300;
  private String groupsBodyOverride;

  public record Group(
      String id, String name, String path, String parentId, List<String> memberIds) {}

  public FakeKeycloakServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/realms/" + REALM + "/protocol/openid-connect/token", this::token);
    server.createContext("/admin/realms/" + REALM + "/groups", this::groups);
    server.createContext("/admin/realms/" + REALM + "/users", this::users);
    server.start();
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public String issuerUri() {
    return baseUrl() + "/realms/" + REALM;
  }

  public int tokenRequests() {
    return tokenRequests.get();
  }

  public List<String> requestedPaths() {
    return List.copyOf(requestedPaths);
  }

  public FakeKeycloakServer withGroup(
      String id, String name, String path, String parentId, String... memberIds) {
    groups.put(id, new Group(id, name, path, parentId, List.of(memberIds)));
    return this;
  }

  public FakeKeycloakServer withGroupOfManyMembers(
      String id, String name, String path, String parentId, int memberCount) {
    List<String> members = new ArrayList<>();
    for (int i = 0; i < memberCount; i++) {
      members.add(id + "-member-" + i);
    }
    groups.put(id, new Group(id, name, path, parentId, members));
    return this;
  }

  /** One account of the realm with its {@code enabled} flag (#1818). */
  public FakeKeycloakServer withAccount(String id, boolean enabled) {
    accounts.put(id, enabled);
    return this;
  }

  /** {@code count} enabled accounts - for the pagination and the ceiling of the account list. */
  public FakeKeycloakServer withAccounts(int count) {
    for (int i = 0; i < count; i++) {
      accounts.put("u-" + i, true);
    }
    return this;
  }

  /** The secret this double accepts, for a value with characters a form encoding must escape. */
  public FakeKeycloakServer expectingSecret(String secret) {
    this.expectedSecret = secret;
    return this;
  }

  public FakeKeycloakServer failingAdminCallsWith(int status) {
    this.adminStatus = status;
    return this;
  }

  public FakeKeycloakServer withTokenLifetime(long seconds) {
    this.tokenLifetimeSeconds = seconds;
    return this;
  }

  public FakeKeycloakServer answeringGroupsWith(String body) {
    this.groupsBodyOverride = body;
    return this;
  }

  @Override
  public void close() {
    server.stop(0);
  }

  // ---------------------------------------------------------------------------------- handlers

  private void token(HttpExchange exchange) throws IOException {
    tokenRequests.incrementAndGet();
    String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    Map<String, String> fields = parseForm(form);
    if (!CLIENT_ID.equals(fields.get("client_id"))
        || !expectedSecret.equals(fields.get("client_secret"))
        || !"client_credentials".equals(fields.get("grant_type"))) {
      respond(exchange, 401, "{\"error\":\"unauthorized_client\"}");
      return;
    }
    respond(
        exchange,
        200,
        "{\"access_token\":\"token-"
            + tokenRequests.get()
            + "\",\"expires_in\":"
            + tokenLifetimeSeconds
            + "}");
  }

  private void groups(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
    requestedPaths.add(
        path + (query.isEmpty() ? "" : "?" + exchange.getRequestURI().getRawQuery()));
    if (!exchange.getRequestHeaders().getFirst("Authorization").startsWith("Bearer token-")) {
      respond(exchange, 401, "{\"error\":\"HTTP 401 Unauthorized\"}");
      return;
    }
    if (adminStatus != null) {
      respond(exchange, adminStatus, "{\"error\":\"nope\"}");
      return;
    }
    String suffix = path.substring(("/admin/realms/" + REALM + "/groups").length());
    if (suffix.equals("/count")) {
      respond(exchange, 200, "{\"count\":" + groups.size() + "}");
      return;
    }
    if (suffix.isEmpty() || suffix.equals("/")) {
      if (groupsBodyOverride != null) {
        respond(exchange, 200, groupsBodyOverride);
        return;
      }
      respond(exchange, 200, groupArray(childrenOf(null), query));
      return;
    }
    String[] parts = suffix.substring(1).split("/");
    String groupId = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
    if (parts.length == 2 && parts[1].equals("children")) {
      respond(exchange, 200, groupArray(childrenOf(groupId), query));
      return;
    }
    if (parts.length == 2 && parts[1].equals("members")) {
      Group group = groups.get(groupId);
      List<String> members = group == null ? List.of() : group.memberIds();
      respond(exchange, 200, memberArray(page(members, query)));
      return;
    }
    respond(exchange, 404, "{\"error\":\"unknown\"}");
  }

  private void users(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
    requestedPaths.add(
        path + (query.isEmpty() ? "" : "?" + exchange.getRequestURI().getRawQuery()));
    if (!exchange.getRequestHeaders().getFirst("Authorization").startsWith("Bearer token-")) {
      respond(exchange, 401, "{\"error\":\"HTTP 401 Unauthorized\"}");
      return;
    }
    if (adminStatus != null) {
      respond(exchange, adminStatus, "{\"error\":\"nope\"}");
      return;
    }
    respond(exchange, 200, accountArray(page(List.copyOf(accounts.keySet()), query)));
  }

  // ------------------------------------------------------------------------------------ bodies

  private String accountArray(List<String> ids) {
    StringBuilder body = new StringBuilder("[");
    for (int i = 0; i < ids.size(); i++) {
      if (i > 0) {
        body.append(',');
      }
      body.append("{\"id\":\"")
          .append(ids.get(i))
          .append("\",\"username\":\"u")
          .append(i)
          .append("\",\"enabled\":")
          .append(accounts.get(ids.get(i)))
          .append('}');
    }
    return body.append(']').toString();
  }

  private List<Group> childrenOf(String parentId) {
    List<Group> result = new ArrayList<>();
    for (Group group : groups.values()) {
      if (java.util.Objects.equals(group.parentId(), parentId)) {
        result.add(group);
      }
    }
    return result;
  }

  private String groupArray(List<Group> all, Map<String, String> query) {
    StringBuilder body = new StringBuilder("[");
    List<Group> page = page(all, query);
    for (int i = 0; i < page.size(); i++) {
      Group group = page.get(i);
      if (i > 0) {
        body.append(',');
      }
      body.append("{\"id\":\"")
          .append(group.id())
          .append("\",\"name\":\"")
          .append(group.name())
          .append("\",\"path\":\"")
          .append(group.path())
          .append("\",\"subGroups\":[],\"subGroupCount\":")
          .append(childrenOf(group.id()).size());
      if (group.parentId() != null) {
        body.append(",\"parentId\":\"").append(group.parentId()).append('"');
      }
      body.append('}');
    }
    return body.append(']').toString();
  }

  private String memberArray(List<String> memberIds) {
    StringBuilder body = new StringBuilder("[");
    for (int i = 0; i < memberIds.size(); i++) {
      if (i > 0) {
        body.append(',');
      }
      body.append("{\"id\":\"")
          .append(memberIds.get(i))
          .append("\",\"username\":\"u")
          .append(i)
          .append("\",\"enabled\":true}");
    }
    return body.append(']').toString();
  }

  private <T> List<T> page(List<T> all, Map<String, String> query) {
    int first = Integer.parseInt(query.getOrDefault("first", "0"));
    int max = Integer.parseInt(query.getOrDefault("max", "100"));
    if (first >= all.size()) {
      return List.of();
    }
    return all.subList(first, Math.min(all.size(), first + max));
  }

  private static Map<String, String> parseForm(String form) {
    Map<String, String> fields = new LinkedHashMap<>();
    if (form == null || form.isBlank()) {
      return fields;
    }
    for (String pair : form.split("&")) {
      int index = pair.indexOf('=');
      if (index > 0) {
        fields.put(
            URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
      }
    }
    return fields;
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
