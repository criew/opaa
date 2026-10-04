package io.opaa.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector.SourceChange;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Transitions through the connector over HTTP: a profile change reaches every library and one
 * refusal changes nothing, connecting checks the defaults and repairs a locked library with an
 * address, releasing keeps the effective configuration, and managers without the release read the
 * profile of their library and its options while others are still refused.
 */
@OpaaIntegrationTest
class ProfileTransitionIntegrationTest {

  private static final String ADMIN = "/api/v1/admin/connection-profiles";
  private static final String PROXY = "proxy.example.org:3128";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private ProfileProbeSourceConnector probe;

  private final List<UUID> profiles = new ArrayList<>();
  private final List<UUID> libraries = new ArrayList<>();

  @BeforeEach
  void clearThePolicyAndTheProbe() {
    jdbc.update("DELETE FROM connector_type_policies WHERE source_type = 'PROFILE_PROBE'");
    probe.changeChecks();
    probe.sourceChanges();
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM connector_type_policies WHERE source_type = 'PROFILE_PROBE'");
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    for (UUID profile : profiles) {
      ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
  }

  @Test
  void aChangedDefaultReachesEveryLibraryAndOneRefusalChangesNothing() throws Exception {
    UUID profile = createProfile("{\"edition\": \"CLOUD\"}", null);
    UUID first = createLibrary(profile, "t1");
    UUID second = createLibrary(profile, "t2");
    UUID third = createLibrary(profile, ProfileProbeSourceConnector.CLOUD_ONLY_TOPIC);
    probe.changeChecks();

    mockMvc
        .perform(as("dev-admin", post(ADMIN + "/" + profile + "/impact")).content(change(profile)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.libraries").value(3))
        .andExpect(jsonPath("$.rejectedLibraries").value(1))
        .andExpect(jsonPath("$.rejections[0].libraryId").value(third.toString()))
        .andExpect(jsonPath("$.rejections[0].category").value("SETTINGS"))
        .andExpect(
            jsonPath("$.rejections[0].message").value(Matchers.containsString("Edition CLOUD")));
    mockMvc
        .perform(as("dev-admin", put(ADMIN + "/" + profile)).content(change(profile)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONNECTION_PROFILE_CHANGE_REJECTED"));

    assertThat(
            jdbc.queryForObject(
                "SELECT connector_settings FROM connection_profiles WHERE id = ?",
                String.class,
                profile))
        .contains("CLOUD");
    assertThat(probe.sourceChanges()).isEmpty();
    assertThat(editionEntries(first)).isZero();

    mockMvc
        .perform(
            as("dev-user", put("/api/v1/libraries/" + third))
                .content("{\"name\": \"Ablage\", \"sourceSettings\": {\"topic\": \"t3\"}}"))
        .andExpect(status().isOk());
    probe.changeChecks();
    probe.sourceChanges();
    mockMvc
        .perform(as("dev-admin", put(ADMIN + "/" + profile)).content(change(profile)))
        .andExpect(status().isOk());

    assertThat(probe.changeChecks()).hasSize(3);
    assertThat(probe.sourceChanges())
        .extracting(SourceChange::libraryId)
        .containsExactlyInAnyOrder(first, second, third);
    assertThat(editionEntries(first)).isOne();
    assertThat(editionEntries(third)).isOne();
  }

  @Test
  void connectingWithADefaultTheConnectorRefusesIsA400AndLeavesTheLibraryOnItsOwn()
      throws Exception {
    UUID profile = createProfile("{\"edition\": \"DC\"}", null);
    UUID library =
        createOwnLibrary(
            "https://probe.example.org/x",
            "\"sourceSettings\": {\"topic\": \""
                + ProfileProbeSourceConnector.CLOUD_ONLY_TOPIC
                + "\"},");

    mockMvc
        .perform(connect(library, profile, null))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("Edition CLOUD")));

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM library_connections WHERE library_id = ?",
                Integer.class,
                library))
        .isZero();
  }

  /** Rule 2: what the profile set becomes the library's own, so it runs on unchanged. */
  @Test
  void releasingWritesTheDefaultsProxyAndTlsSwitchIntoTheLibrary() throws Exception {
    UUID profile = createProfile("{\"edition\": \"DC\"}", PROXY);
    UUID library = createLibrary(profile, "t");

    mockMvc
        .perform(as("dev-admin", delete("/api/v1/libraries/" + library + "/connection-profile")))
        .andExpect(status().isOk());

    assertThat(stored(library, "source_settings")).contains("DC").contains("\"t\"");
    assertThat(stored(library, "source_proxy")).isEqualTo(PROXY);
    assertThat(stored(library, "source_insecure_ssl")).isEqualTo("true");
    assertThat(probe.sourceChanges()).isEmpty();
  }

  @Test
  void aLibraryTheProfileRequirementLocksIsRepairedByConnectingItWithAnAddress()
      throws Exception {
    UUID profile = createProfile(null, null);
    UUID library = createOwnLibrary("https://eigen.example.org/x", "");
    mockMvc
        .perform(
            as("dev-admin", put("/api/v1/admin/connector-types/PROFILE_PROBE/profile-requirement"))
                .content("{\"required\": true, \"ownAddressStock\": \"LOCKED\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(as("dev-admin", get("/api/v1/libraries/" + library)))
        .andExpect(jsonPath("$.sourceBlock.reason").value("PROFILE_REQUIRED"));

    mockMvc.perform(connect(library, profile, null)).andExpect(status().isBadRequest());
    mockMvc
        .perform(connect(library, profile, "https://probe.example.org/x"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connectionProfile.id").value(profile.toString()))
        .andExpect(jsonPath("$.sourceUrl").value("https://probe.example.org/x"))
        .andExpect(jsonPath("$.sourceBlock").doesNotExist());
  }

  @Test
  void aManagerWithoutTheReleaseReadsTheProfileOfTheLibraryAndItsOptions() throws Exception {
    UUID profile = createProfile("{\"edition\": \"DC\"}", PROXY);
    UUID library = createLibrary(profile, "t");
    ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);

    mockMvc
        .perform(as("dev-user", get("/api/v1/libraries/" + library)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connectionProfile.serverUrl").value("https://probe.example.org"))
        .andExpect(jsonPath("$.connectionProfile.authMethod").value("NONE"))
        .andExpect(jsonPath("$.connectionProfile.connectorDefaults.edition").value("DC"))
        .andExpect(jsonPath("$.connectionProfile.sourceProxy").value(PROXY))
        .andExpect(jsonPath("$.connectionProfile.sourceInsecureSsl").value(true));
    mockMvc
        .perform(
            as(
                "dev-user",
                get("/api/v1/connection-profiles?sourceType=PROFILE_PROBE&libraryId=" + library)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + profile + "')].creatable").value(false))
        .andExpect(
            jsonPath("$[?(@.id == '" + profile + "')].creationNotice")
                .value(Matchers.everyItem(Matchers.notNullValue())));
    mockMvc
        .perform(
            as(
                "dev-user",
                get("/api/v1/connection-profiles?sourceType=RSS_FEED&libraryId=" + library)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void someoneWithoutARightOnTheLibraryIsStillRefused() throws Exception {
    UUID library = createOwnLibrary("https://probe.example.org/x", "");

    int status =
        mockMvc
            .perform(
                as(
                    "dev-user",
                    get(
                        "/api/v1/connection-profiles?sourceType=PROFILE_PROBE&libraryId="
                            + library)))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).isIn(403, 404);
  }

  private long editionEntries(UUID library) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE object_id = ? AND event_type ="
            + " 'LIBRARY_SOURCE_UPDATED' AND after::text LIKE '%edition%'",
        Long.class,
        library.toString());
  }

  private String change(UUID profile) throws Exception {
    String name =
        JsonPath.read(
            mockMvc
                .perform(as("dev-admin", get(ADMIN + "/" + profile)))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8),
            "$.name");
    return """
        {"name": "%s", "serverUrl": "https://probe.example.org", "authMethod": "NONE",
         "ownership": "LIBRARY", "connectorSettings": {"edition": "DC"}}
        """
        .formatted(name);
  }

  private MockHttpServletRequestBuilder connect(UUID library, UUID profile, String sourceUrl) {
    return as("dev-admin", put("/api/v1/libraries/" + library + "/connection-profile"))
        .content(
            "{\"profileId\": \""
                + profile
                + "\""
                + (sourceUrl == null ? "" : ", \"sourceUrl\": \"" + sourceUrl + "\"")
                + "}");
  }

  private UUID createProfile(String defaults, String proxy) throws Exception {
    String body =
        mockMvc
            .perform(
                as("dev-admin", post(ADMIN))
                    .content(
                        """
                        {"name": "Zugang Übergang %s", "sourceType": "PROFILE_PROBE",
                         "serverUrl": "https://probe.example.org", "authMethod": "NONE",
                         "ownership": "LIBRARY", "sourceInsecureSsl": %s%s%s}
                        """
                            .formatted(
                                UUID.randomUUID(),
                                proxy != null,
                                defaults == null ? "" : ", \"connectorSettings\": " + defaults,
                                proxy == null ? "" : ", \"sourceProxy\": \"" + proxy + "\"")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
    profiles.add(id);
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + id);
    return id;
  }

  /** A library of dev-user through {@code profile}, with {@code topic}. */
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
  private UUID createOwnLibrary(String url, String extra) throws Exception {
    return created(
        as("dev-admin", post("/api/v1/libraries"))
            .content(
                "{\"name\": \"Eigen "
                    + UUID.randomUUID()
                    + "\", \"sourceType\": \"PROFILE_PROBE\", "
                    + extra
                    + "\"sourceUrl\": \""
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

  private String stored(UUID library, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + "::text FROM knowledge_libraries WHERE id = ?", String.class, library);
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
