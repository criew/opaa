package io.opaa.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector.ChangeCheck;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector.Pause;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A profile change and the writes of a library through that profile - connecting, creating,
 * releasing, changing its settings - on real transactions, the interleaving forced by holding one
 * side within its write: afterwards a library on the profile either was checked by its connector
 * under the profile's new configuration, or the write through the old one did not happen.
 */
@OpaaIntegrationTest
class ProfileSerializationIntegrationTest {

  private static final String ADMIN = "/api/v1/admin/connection-profiles";
  private static final String PROXY = "proxy.example.org:3128";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private ProfileProbeSourceConnector probe;

  private final List<UUID> profiles = new ArrayList<>();
  private final List<UUID> libraries = new ArrayList<>();
  private final ExecutorService executor = Executors.newFixedThreadPool(2);

  @BeforeEach
  void forgetWhatTheProbeSaw() {
    probe.clearPause();
    probe.changeChecks();
    probe.sourceChanges();
    probe.validations();
  }

  @AfterEach
  void tearDown() {
    probe.clearPause();
    executor.shutdownNow();
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    for (UUID profile : profiles) {
      ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
  }

  @Test
  void aProfileChangeWaitsForAConnectionAndChecksTheConnectedLibrary() throws Exception {
    UUID profile = createProfile();
    String url = "https://probe.example.org/zuerst";
    UUID library = createOwnLibrary(url);
    Pause connecting = probe.pauseSourceChange(library);

    Future<Integer> connect = executor.submit(() -> statusOf(connect(library, profile)));
    connecting.awaitReached();
    probe.changeChecks();
    probe.validations();
    Future<Integer> change = executor.submit(() -> statusOf(changeProxy(profile)));
    waitUntilFinishedOrBlocked(change);
    connecting.release();

    assertThat(connect.get(30, TimeUnit.SECONDS)).isEqualTo(200);
    assertThat(change.get(30, TimeUnit.SECONDS)).isEqualTo(200);
    assertThat(connectedTo(library)).isEqualTo(profile);
    assertThat(checkedWithProxy(url)).isTrue();
  }

  @Test
  void aConnectionDuringAProfileChangeIsCheckedUnderTheNewConfigurationOrNotMade()
      throws Exception {
    UUID profile = createProfile();
    Pause changing = probe.pauseSourceChange(createLibrary(profile, "vorhanden"));
    String url = "https://probe.example.org/waehrenddessen";
    UUID library = createOwnLibrary(url);

    Future<Integer> change = executor.submit(() -> statusOf(changeProxy(profile)));
    changing.awaitReached();
    Future<Integer> connect = executor.submit(() -> statusOf(connect(library, profile)));
    waitUntilFinishedOrBlocked(connect);
    changing.release();

    assertThat(change.get(30, TimeUnit.SECONDS)).isEqualTo(200);
    assertThat(connect.get(30, TimeUnit.SECONDS)).isIn(200, 409);
    if (profile.equals(connectedTo(library))) {
      assertThat(checkedWithProxy(url))
          .as("connected without a check under the new proxy")
          .isTrue();
    }
  }

  @Test
  void aLibraryCreatedDuringAProfileChangeIsCheckedUnderTheNewConfigurationOrNotCreated()
      throws Exception {
    UUID profile = createProfile();
    Pause changing = probe.pauseSourceChange(createLibrary(profile, "vorhanden"));
    String name = "Neu " + UUID.randomUUID();
    String url = "https://probe.example.org/neu";

    Future<Integer> change = executor.submit(() -> statusOf(changeProxy(profile)));
    changing.awaitReached();
    Future<Integer> create =
        executor.submit(
            () ->
                statusOf(
                    as("dev-user", post("/api/v1/libraries"))
                        .content(
                            """
                            {"name": "%s", "sourceType": "PROFILE_PROBE", "sourceUrl": "%s",
                             "connectionProfileId": "%s"}
                            """
                                .formatted(name, url, profile))));
    waitUntilFinishedOrBlocked(create);
    changing.release();

    assertThat(change.get(30, TimeUnit.SECONDS)).isEqualTo(200);
    assertThat(create.get(30, TimeUnit.SECONDS)).isIn(201, 409);
    List<UUID> created =
        jdbc.queryForList("SELECT id FROM assets WHERE name = ?", UUID.class, name);
    libraries.addAll(created);
    if (!created.isEmpty()) {
      assertThat(checkedWithProxy(url)).as("created without a check under the new proxy").isTrue();
    }
  }

  @Test
  void aReleaseDuringAProfileChangeTakesTheNewConfigurationOrDoesNotHappen() throws Exception {
    UUID profile = createProfile();
    Pause changing = probe.pauseSourceChange(createLibrary(profile, "vorhanden"));
    UUID library = createLibrary(profile, "loesen");

    Future<Integer> change = executor.submit(() -> statusOf(changeProxy(profile)));
    changing.awaitReached();
    Future<Integer> release =
        executor.submit(
            () ->
                statusOf(
                    as(
                        "dev-admin",
                        delete("/api/v1/libraries/" + library + "/connection-profile"))));
    waitUntilFinishedOrBlocked(release);
    changing.release();

    assertThat(change.get(30, TimeUnit.SECONDS)).isEqualTo(200);
    assertThat(release.get(30, TimeUnit.SECONDS)).isIn(200, 409);
    if (connectedTo(library) == null) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT source_proxy FROM knowledge_libraries WHERE id = ?",
                  String.class,
                  library))
          .as("released with the proxy the profile no longer sets")
          .isEqualTo(PROXY);
    }
  }

  @Test
  void aSettingsChangeDuringAProfileChangeIsCheckedUnderTheNewConfigurationOrNotMade()
      throws Exception {
    UUID profile = createProfile();
    Pause changing = probe.pauseSourceChange(createLibrary(profile, "vorhanden"));
    String url = "https://probe.example.org/alt";
    UUID library = createLibrary(profile, "alt");

    Future<Integer> change = executor.submit(() -> statusOf(changeProxy(profile)));
    changing.awaitReached();
    Future<Integer> update =
        executor.submit(
            () ->
                statusOf(
                    as("dev-user", put("/api/v1/libraries/" + library))
                        .content(
                            "{\"name\": \"Geändert\", \"sourceSettings\": {\"topic\": \"neu\"}}")));
    waitUntilFinishedOrBlocked(update);
    changing.release();

    assertThat(change.get(30, TimeUnit.SECONDS)).isEqualTo(200);
    assertThat(update.get(30, TimeUnit.SECONDS)).isIn(200, 409);
    String settings =
        jdbc.queryForObject(
            "SELECT source_settings::text FROM knowledge_libraries WHERE id = ?",
            String.class,
            library);
    if (settings.contains("\"neu\"")) {
      assertThat(
              probe.changeChecks().stream()
                  .map(ChangeCheck::requested)
                  .anyMatch(
                      requested ->
                          url.equals(requested.sourceUrl())
                              && PROXY.equals(requested.sourceProxy())
                              && requested.connectorSettings() != null
                              && "neu".equals(requested.connectorSettings().get("topic"))))
          .as("changed without a check under the new proxy")
          .isTrue();
    }
  }

  /** Whether the connector checked a configuration at {@code url} through the new proxy. */
  private boolean checkedWithProxy(String url) {
    return Stream.concat(
            probe.validations().stream(), probe.changeChecks().stream().map(ChangeCheck::requested))
        .anyMatch(
            settings -> url.equals(settings.sourceUrl()) && PROXY.equals(settings.sourceProxy()));
  }

  private UUID connectedTo(UUID library) {
    return jdbc
        .queryForList(
            "SELECT profile_id FROM library_connections WHERE library_id = ?", UUID.class, library)
        .stream()
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  /** Waits until {@code call} is done or some session of the database waits for a lock. */
  private void waitUntilFinishedOrBlocked(Future<?> call) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!call.isDone() && System.nanoTime() < deadline) {
      Long waiting =
          jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Long.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(20);
    }
  }

  private int statusOf(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request).andReturn().getResponse().getStatus();
  }

  private MockHttpServletRequestBuilder connect(UUID library, UUID profile) {
    return as("dev-admin", put("/api/v1/libraries/" + library + "/connection-profile"))
        .content("{\"profileId\": \"" + profile + "\"}");
  }

  private MockHttpServletRequestBuilder changeProxy(UUID profile) throws Exception {
    String name =
        JsonPath.read(
            mockMvc
                .perform(as("dev-admin", get(ADMIN + "/" + profile)))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8),
            "$.name");
    return as("dev-admin", put(ADMIN + "/" + profile))
        .content(
            """
            {"name": "%s", "serverUrl": "https://probe.example.org", "authMethod": "NONE",
             "ownership": "LIBRARY", "connectorSettings": {"edition": "DC"},
             "sourceProxy": "%s"}
            """
                .formatted(name, PROXY));
  }

  private UUID createProfile() throws Exception {
    String body =
        mockMvc
            .perform(
                as("dev-admin", post(ADMIN))
                    .content(
                        """
                        {"name": "Zugang Reihenfolge %s", "sourceType": "PROFILE_PROBE",
                         "serverUrl": "https://probe.example.org", "authMethod": "NONE",
                         "ownership": "LIBRARY", "connectorSettings": {"edition": "DC"}}
                        """
                            .formatted(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
    profiles.add(id);
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + id);
    return id;
  }

  /** A library of dev-user through {@code profile} at {@code topic} under its address. */
  private UUID createLibrary(UUID profile, String topic) throws Exception {
    return created(
        as("dev-user", post("/api/v1/libraries"))
            .content(
                """
                {"name": "Bibliothek %s", "sourceType": "PROFILE_PROBE",
                 "sourceUrl": "https://probe.example.org/%s",
                 "sourceSettings": {"topic": "%s"}, "connectionProfileId": "%s"}
                """
                    .formatted(UUID.randomUUID(), topic, topic, profile)));
  }

  /** A library of dev-admin with its own address. */
  private UUID createOwnLibrary(String url) throws Exception {
    return created(
        as("dev-admin", post("/api/v1/libraries"))
            .content(
                "{\"name\": \"Eigen "
                    + UUID.randomUUID()
                    + "\", \"sourceType\": \"PROFILE_PROBE\", \"sourceUrl\": \""
                    + url
                    + "\"}"));
  }

  private UUID created(MockHttpServletRequestBuilder request) throws Exception {
    String body =
        mockMvc
            .perform(request)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
    libraries.add(id);
    return id;
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
