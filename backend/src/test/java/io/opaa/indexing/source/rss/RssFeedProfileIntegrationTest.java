package io.opaa.indexing.source.rss;

import static io.opaa.test.ProfileLibraries.ADMIN;
import static io.opaa.test.ProfileLibraries.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * An {@code RSS_FEED} library through a profile ("Zugang"): without sign-in the feed and its detail
 * pages are read anonymously; with a personal secret only the feed's own origin receives it, a
 * detail page on a foreign server never does - which is why the profile requirement names the
 * detail pages as what it leaves open. The requirement itself is switched for this shipped type
 * with either stock choice.
 */
@OpaaIntegrationTest
class RssFeedProfileIntegrationTest {

  private static final String REQUIREMENT =
      "/api/v1/admin/connector-types/RSS_FEED/profile-requirement";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures fixtures;

  private ProfileLibraries api;
  private HttpServer feedServer;
  private HttpServer foreignServer;
  private final List<String> feedAuthorizations = new CopyOnWriteArrayList<>();
  private final List<String> foreignAuthorizations = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    clearThePolicy();
    api = new ProfileLibraries(mockMvc, jdbc, fixtures);
    foreignServer = serve(foreignAuthorizations, false);
    feedServer = serve(feedAuthorizations, true);
  }

  @AfterEach
  void tearDown() {
    clearThePolicy();
    api.cleanUp();
    feedServer.stop(0);
    foreignServer.stop(0);
  }

  private void clearThePolicy() {
    jdbc.update("DELETE FROM connector_type_policies WHERE source_type = 'RSS_FEED'");
  }

  @Test
  void withoutSignInTheFeedAndEveryDetailPageAreReadAnonymously() throws Exception {
    UUID profile = api.createProfile(profileJson("NONE"));
    UUID library = api.createLibrary(libraryJson(profile, ""));

    String status = api.run(library);

    assertThat(JsonPath.<String>read(status, "$.status")).as(status).isEqualTo("COMPLETED");
    assertThat(JsonPath.<Integer>read(status, "$.documentsIndexedTotal")).as(status).isEqualTo(2);
    assertThat(feedAuthorizations).isNotEmpty().containsOnly("null");
    assertThat(foreignAuthorizations).isNotEmpty().containsOnly("null");
  }

  @Test
  void aPersonalSecretReachesTheFeedsOriginButNoForeignDetailPage() throws Exception {
    UUID profile = api.createProfile(profileJson("PERSONAL_SECRET"));
    UUID library =
        api.createLibrary(libraryJson(profile, "\"sourceCredentials\": \"leser:geheim\","));

    String status = api.run(library);

    assertThat(JsonPath.<String>read(status, "$.status")).as(status).isEqualTo("COMPLETED");
    String basic =
        "Basic "
            + Base64.getEncoder().encodeToString("leser:geheim".getBytes(StandardCharsets.UTF_8));
    assertThat(feedAuthorizations).hasSizeGreaterThanOrEqualTo(2).containsOnly(basic);
    assertThat(foreignAuthorizations).isNotEmpty().containsOnly("null");
  }

  /**
   * The requirement is switchable for every shipped remote type that reads with a library's own
   * secret, and for RSS with either stock - naming the detail pages it leaves open.
   */
  @Test
  void theRequirementIsSwitchedOnForAShippedTypeWithEitherStock() throws Exception {
    for (String type : List.of("HTTP_DIRECTORY", "RSS_FEED", "CONFLUENCE", "NEXTCLOUD", "S3")) {
      mockMvc
          .perform(as(ADMIN, get("/api/v1/admin/connector-types/" + type + "/profile-requirement")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.state.profileSupport").value("OPTIONAL"));
    }
    mockMvc
        .perform(as(ADMIN, get("/api/v1/admin/connector-types/SMB/profile-requirement")))
        .andExpect(jsonPath("$.state.profileSupport").value("OPTIONAL"));
    UUID own = api.createLibrary(ownLibraryJson());
    api.createProfile(profileJson("NONE"));
    mockMvc
        .perform(as(ADMIN, get(REQUIREMENT)))
        .andExpect(jsonPath("$.switchable").value(true))
        .andExpect(
            jsonPath("$.coverageNotice").value(Matchers.containsString("ohne Zugangsdaten")));

    mockMvc
        .perform(
            as(ADMIN, put(REQUIREMENT))
                .content("{\"required\": true, \"ownAddressStock\": \"RUNS\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ownAddressStock").value("RUNS"));
    assertThat(JsonPath.<String>read(api.run(own), "$.status")).isEqualTo("COMPLETED");

    mockMvc
        .perform(
            as(ADMIN, put(REQUIREMENT))
                .content("{\"required\": true, \"ownAddressStock\": \"LOCKED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ownAddressStock").value("LOCKED"));
    mockMvc
        .perform(as(ADMIN, get("/api/v1/libraries/" + own)))
        .andExpect(jsonPath("$.sourceBlock.reason").value("PROFILE_REQUIRED"));
  }

  private String feedUrl() {
    return "http://127.0.0.1:" + feedServer.getAddress().getPort() + "/feed.xml";
  }

  private String feed() {
    String own = "http://127.0.0.1:" + feedServer.getAddress().getPort() + "/artikel/eins";
    String foreign = "http://127.0.0.1:" + foreignServer.getAddress().getPort() + "/artikel/zwei";
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>Amtsblatt</title><link>%s</link>
        <description>Bekanntmachungen</description>
        <item><title>Erste Bekanntmachung</title><link>%s</link>
         <pubDate>Mon, 05 Oct 2026 08:00:00 GMT</pubDate></item>
        <item><title>Zweite Bekanntmachung</title><link>%s</link>
         <pubDate>Mon, 05 Oct 2026 09:00:00 GMT</pubDate></item>
        </channel></rss>
        """
        .formatted(feedUrl(), own, foreign);
  }

  private String profileJson(String method) {
    return """
        {"name": "Zugang RSS %s", "sourceType": "RSS_FEED",
         "serverUrl": "http://127.0.0.1:%d", "authMethod": "%s", "ownership": "LIBRARY"}
        """
        .formatted(UUID.randomUUID(), feedServer.getAddress().getPort(), method);
  }

  private String libraryJson(UUID profile, String extra) {
    return """
        {"name": "Feed %s", "sourceType": "RSS_FEED", %s
         "sourceUrl": "%s", "connectionProfileId": "%s"}
        """
        .formatted(UUID.randomUUID(), extra, feedUrl(), profile);
  }

  private String ownLibraryJson() {
    return """
        {"name": "Eigener Feed %s", "sourceType": "RSS_FEED", "sourceUrl": "%s"}
        """
        .formatted(UUID.randomUUID(), feedUrl());
  }

  /**
   * A loopback server answering {@code /feed.xml} with the feed (when {@code servesFeed}) and every
   * other path with a detail page, recording each request's {@code Authorization} in {@code seen}.
   */
  private HttpServer serve(List<String> seen, boolean servesFeed) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          seen.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
          if (servesFeed && exchange.getRequestURI().getPath().equals("/feed.xml")) {
            respond(exchange, "application/rss+xml", feed());
          } else {
            respond(
                exchange,
                "text/html; charset=utf-8",
                "<html><head><title>Bekanntmachung</title></head><body><article><p>Die"
                    + " Gemeinde gibt bekannt, dass der Bebauungsplan ausgelegt wird.</p>"
                    + "</article></body></html>");
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
