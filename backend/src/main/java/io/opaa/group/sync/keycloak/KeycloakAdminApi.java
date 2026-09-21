package io.opaa.group.sync.keycloak;

import io.opaa.group.sync.DirectoryUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The raw calls of the Keycloak Admin REST API one directory run needs (#1817): a service-account
 * sign-in and the three paginated reads {@code /groups}, {@code /groups/{id}/children} and {@code
 * /groups/{id}/members}. No redirect is ever followed - the address passed the policy, a redirect
 * target would not have.
 *
 * <p><b>Every failure is a {@link DirectoryUnavailableException}</b>, including a rejected sign-in
 * and a {@code 403} from a service account that lacks {@code view-users}/{@code query-groups}.
 * Anything else would let a rights problem at the source look like "this directory has no groups",
 * and that is the one reading a synchronisation must never act on (#237).
 *
 * <p>The access token lives about five minutes; a large realm outlasts it. The token is therefore
 * re-fetched once whenever the current one is within {@link #TOKEN_REFRESH_MARGIN} of expiry, so a
 * long traversal does not fail halfway through.
 */
class KeycloakAdminApi {

  private static final Logger log = LoggerFactory.getLogger(KeycloakAdminApi.class);

  private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofSeconds(30);
  private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpClient httpClient;
  private final KeycloakRealmAddress address;
  private final String clientId;
  private final String clientSecret;
  private final Duration requestTimeout;

  private String accessToken;
  private Instant accessTokenExpiry = Instant.EPOCH;

  KeycloakAdminApi(
      HttpClient httpClient,
      KeycloakRealmAddress address,
      String clientId,
      String clientSecret,
      Duration requestTimeout) {
    this.httpClient = httpClient;
    this.address = address;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.requestTimeout = requestTimeout;
  }

  /** One page of the realm's top-level groups. */
  List<JsonNode> topLevelGroups(int first, int max) throws DirectoryUnavailableException {
    return array(adminGet("/groups?first=" + first + "&max=" + max));
  }

  /** One page of a group's direct child groups. */
  List<JsonNode> childGroups(String groupId, int first, int max)
      throws DirectoryUnavailableException {
    return array(
        adminGet("/groups/" + encode(groupId) + "/children?first=" + first + "&max=" + max));
  }

  /**
   * One page of a group's <b>direct</b> members - {@code /members} calls {@code
   * getGroupMembersStream} and does not descend into subgroups (ADR-0036, Entscheidung 3).
   */
  List<JsonNode> members(String groupId, int first, int max) throws DirectoryUnavailableException {
    return array(
        adminGet(
            "/groups/"
                + encode(groupId)
                + "/members?first="
                + first
                + "&max="
                + max
                + "&briefRepresentation=true"));
  }

  /**
   * One page of the realm's accounts with their {@code enabled} flag (#1818). {@code
   * briefRepresentation} keeps id and {@code enabled} and drops the attributes, credentials and
   * role mappings a run has no business reading.
   */
  List<JsonNode> users(int first, int max) throws DirectoryUnavailableException {
    return array(adminGet("/users?first=" + first + "&max=" + max + "&briefRepresentation=true"));
  }

  /** The realm's total number of groups, subgroups included - the connection test's evidence. */
  int groupCount() throws DirectoryUnavailableException {
    JsonNode node = adminGet("/groups/count");
    return node.path("count").asInt(0);
  }

  private List<JsonNode> array(JsonNode node) throws DirectoryUnavailableException {
    if (!node.isArray()) {
      throw new DirectoryUnavailableException(
          "Das Verzeichnis hat keine Liste geliefert, wie die Admin-API es tut.");
    }
    List<JsonNode> result = new ArrayList<>();
    node.forEach(result::add);
    return result;
  }

  private JsonNode adminGet(String pathAndQuery) throws DirectoryUnavailableException {
    String url = address.baseUrl() + "/admin/realms/" + encode(address.realm()) + pathAndQuery;
    String body = get(url, accessToken());
    try {
      return JSON.readTree(body);
    } catch (RuntimeException e) {
      throw new DirectoryUnavailableException(
          "Das Verzeichnis hat kein gültiges JSON geliefert.", e);
    }
  }

  private synchronized String accessToken() throws DirectoryUnavailableException {
    if (accessToken != null && Instant.now().isBefore(accessTokenExpiry)) {
      return accessToken;
    }
    String url =
        address.baseUrl() + "/realms/" + encode(address.realm()) + "/protocol/openid-connect/token";
    String form =
        "grant_type=client_credentials&client_id="
            + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
            + "&client_secret="
            + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
    String body = post(url, form);
    JsonNode token;
    try {
      token = JSON.readTree(body);
    } catch (RuntimeException e) {
      throw new DirectoryUnavailableException(
          "Die Anmeldung am Verzeichnis hat kein gültiges JSON geliefert.", e);
    }
    String value = token.path("access_token").asString(null);
    if (value == null || value.isBlank()) {
      throw new DirectoryUnavailableException(
          "Die Anmeldung am Verzeichnis hat kein Zugangstoken geliefert.");
    }
    long expiresIn = token.path("expires_in").asLong(60);
    accessToken = value;
    accessTokenExpiry = Instant.now().plusSeconds(expiresIn).minus(TOKEN_REFRESH_MARGIN);
    return accessToken;
  }

  private String get(String url, String bearer) throws DirectoryUnavailableException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(uri(url))
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + bearer)
            .GET()
            .build();
    return send(request, url);
  }

  private String post(String url, String form) throws DirectoryUnavailableException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(uri(url))
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
            .build();
    return send(request, url);
  }

  /**
   * One request, no redirect followed, and a status-specific German message - the four a
   * Systemverwaltung can act on ({@code 401}/{@code 403} say "credentials or rights", the rest say
   * "the directory answered with ...") rather than one generic sentence.
   */
  private String send(HttpRequest request, String url) throws DirectoryUnavailableException {
    try {
      HttpResponse<InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      String text;
      try (InputStream body = response.body()) {
        text = new String(body.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
      }
      int status = response.statusCode();
      if (status >= 300 && status < 400) {
        throw new DirectoryUnavailableException(
            "Das Verzeichnis antwortet mit einer Weiterleitung; Weiterleitungen werden nicht"
                + " gefolgt.");
      }
      if (status == 401 || status == 403) {
        throw new DirectoryUnavailableException(
            "Das Dienstkonto wurde abgewiesen (HTTP "
                + status
                + "). Prüfen Sie Client-ID und Geheimnis sowie die Rollen „view-users“ und"
                + " „query-groups“.");
      }
      if (status == 404) {
        throw new DirectoryUnavailableException(
            "Das Verzeichnis kennt den Realm „" + address.realm() + "“ nicht (HTTP 404).");
      }
      if (status != 200) {
        throw new DirectoryUnavailableException(
            "Das Verzeichnis antwortet mit HTTP " + status + ".");
      }
      return text;
    } catch (HttpTimeoutException e) {
      throw new DirectoryUnavailableException(
          "Das Verzeichnis hat nicht rechtzeitig geantwortet.", e);
    } catch (ConnectException | UnknownHostException e) {
      throw new DirectoryUnavailableException("Das Verzeichnis ist nicht erreichbar.", e);
    } catch (IOException e) {
      log.info("Keycloak directory call to {} failed: {}", url, e.getMessage());
      throw new DirectoryUnavailableException(
          "Das Verzeichnis ist nicht erreichbar (" + e.getMessage() + ").", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DirectoryUnavailableException("Der Abruf wurde unterbrochen.", e);
    }
  }

  private URI uri(String url) throws DirectoryUnavailableException {
    try {
      return URI.create(url);
    } catch (IllegalArgumentException e) {
      throw new DirectoryUnavailableException("Die Adresse des Verzeichnisses ist ungültig.", e);
    }
  }

  private static String encode(String segment) {
    return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
