package io.opaa.indexing.source.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.ProfileLibraries;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * An {@code HTTP_DIRECTORY} library through a profile ("Zugang"), run against a loopback autoindex:
 * the address lies under the profile's, a profile without sign-in reaches the server anonymously,
 * one with a personal secret sends the library's own, and the profile's proxy carries the run.
 */
@OpaaIntegrationTest
class HttpDirectoryProfileIntegrationTest {

  private static final String LISTING =
      "<html><head><title>Index of /dokumente/</title></head><body><ul>"
          + "<li><a href=\"Bescheid.txt\">Bescheid.txt</a></li></ul></body></html>";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures fixtures;

  private ProfileLibraries api;
  private HttpServer origin;
  private HttpServer proxy;
  private final List<String> originAuthorizations = new CopyOnWriteArrayList<>();
  private final List<String> proxiedTargets = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    api = new ProfileLibraries(mockMvc, jdbc, fixtures);
    origin = serve(exchange -> originAuthorizations.add(authorization(exchange)));
    proxy = serve(exchange -> proxiedTargets.add(exchange.getRequestURI().toString()));
  }

  @AfterEach
  void tearDown() {
    api.cleanUp();
    origin.stop(0);
    proxy.stop(0);
  }

  @Test
  void aProfileWithoutSignInRunsAnonymouslyAndBindsTheAddress() throws Exception {
    UUID profile = api.createProfile(profileJson("NONE", null));

    api.postLibrary(libraryJson(profile, "http://127.0.0.1:" + proxyPort() + "/dokumente/", ""))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error").value(Matchers.containsString("Server-Adresse des Zugangs")));
    api.postLibrary(libraryJson(profile, address(), "\"sourceCredentials\": \"nutzer:geheim\","))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("sourceCredentials")));
    UUID library = api.createLibrary(libraryJson(profile, address(), ""));

    String status = api.run(library);

    assertThat(JsonPath.<Integer>read(status, "$.documentsIndexedTotal")).as(status).isEqualTo(1);
    assertThat(originAuthorizations).isNotEmpty().containsOnly("null");
  }

  @Test
  void aProfileWithAPersonalSecretSendsTheLibrarysOwn() throws Exception {
    UUID profile = api.createProfile(profileJson("PERSONAL_SECRET", null));
    UUID library =
        api.createLibrary(
            libraryJson(profile, address(), "\"sourceCredentials\": \"nutzer:geheim\","));

    String status = api.run(library);

    assertThat(JsonPath.<Integer>read(status, "$.documentsIndexedTotal")).as(status).isEqualTo(1);
    String basic =
        "Basic "
            + Base64.getEncoder().encodeToString("nutzer:geheim".getBytes(StandardCharsets.UTF_8));
    assertThat(originAuthorizations).isNotEmpty().containsOnly(basic);
  }

  @Test
  void theProfilesProxyCarriesTheRun() throws Exception {
    UUID profile = api.createProfile(profileJson("NONE", "127.0.0.1:" + proxyPort()));
    UUID library = api.createLibrary(libraryJson(profile, address(), ""));

    String status = api.run(library);

    assertThat(JsonPath.<Integer>read(status, "$.documentsIndexedTotal")).as(status).isEqualTo(1);
    assertThat(proxiedTargets).isNotEmpty().allMatch(target -> target.startsWith(address()));
    assertThat(originAuthorizations).as("nothing reached the origin directly").isEmpty();
    assertThat(api.stored(library, "source_proxy")).isNull();
  }

  private String address() {
    return "http://127.0.0.1:" + origin.getAddress().getPort() + "/dokumente/";
  }

  private int proxyPort() {
    return proxy.getAddress().getPort();
  }

  private String profileJson(String method, String proxyAddress) {
    return """
        {"name": "Zugang Web %s", "sourceType": "HTTP_DIRECTORY",
         "serverUrl": "http://127.0.0.1:%d", "authMethod": "%s", "ownership": "LIBRARY"%s}
        """
        .formatted(
            UUID.randomUUID(),
            origin.getAddress().getPort(),
            method,
            proxyAddress == null ? "" : ", \"sourceProxy\": \"" + proxyAddress + "\"");
  }

  private static String libraryJson(UUID profile, String url, String extra) {
    return """
        {"name": "Webverzeichnis %s", "sourceType": "HTTP_DIRECTORY", %s
         "sourceUrl": "%s", "connectionProfileId": "%s"}
        """
        .formatted(UUID.randomUUID(), extra, url, profile);
  }

  private static String authorization(HttpExchange exchange) {
    return String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
  }

  /** A loopback autoindex with one text file, telling {@code seen} of every request. */
  private static HttpServer serve(java.util.function.Consumer<HttpExchange> seen)
      throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          seen.accept(exchange);
          String path = exchange.getRequestURI().getPath();
          if (path.equals("/dokumente/")) {
            respond(exchange, "text/html", LISTING);
          } else if (path.equals("/dokumente/Bescheid.txt")) {
            respond(exchange, "text/plain", "Der Bescheid ergeht wie folgt.");
          } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
          }
        });
    server.start();
    return server;
  }

  private static void respond(HttpExchange exchange, String contentType, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
