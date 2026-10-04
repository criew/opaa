package io.opaa.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.profileprobe.ProfileProbeIndexingExecutor;
import io.opaa.indexing.source.profileprobe.ProfileProbeIndexingExecutor.Seen;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * The draft knows its profile: creation, connection test and change use the profile's defaults,
 * proxy, TLS switch and sign-in; a differing value is a 400; the library stores only its own part;
 * and a test on another profile repairs a library its stored lock would refuse.
 */
@OpaaIntegrationTest
class ProfileDraftIntegrationTest {

  private static final String ADMIN = "/api/v1/admin/connection-profiles";
  private static final String PROXY = "proxy.example.org:3128";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private ProfileProbeIndexingExecutor probeRun;
  @Autowired private ProfileProbeSourceConnector probe;

  private final List<UUID> profiles = new ArrayList<>();
  private final List<UUID> libraries = new ArrayList<>();

  @BeforeEach
  void clearThePolicy() {
    jdbc.update("DELETE FROM connector_type_policies WHERE source_type = 'PROFILE_PROBE'");
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

  /** Rule 1 and the frame: validated and run with the profile's, stored with its own part only. */
  @Test
  void aLibraryIsValidatedAndRunWithTheProfileFrameAndStoresOnlyItsOwnPart() throws Exception {
    UUID profile = createProfile("PERSONAL_SECRET", "{\"edition\": \"DC\"}", PROXY, true);
    probe.lastValidated();

    UUID library =
        createLibrary(
            profile,
            "\"sourceCredentials\": \"nutzer:geheim\", \"sourceSettings\": {\"topic\":"
                + " \"Wetter\"},");

    SourceSettings validated = probe.lastValidated().orElseThrow();
    assertThat(validated.connectorSettings().asMap())
        .isEqualTo(Map.of("edition", "DC", "topic", "Wetter"));
    assertThat(validated.sourceProxy()).isEqualTo(PROXY);
    assertThat(validated.sourceInsecureSsl()).isTrue();
    assertThat(stored(library, "source_settings")).doesNotContain("edition").contains("Wetter");
    assertThat(stored(library, "source_proxy")).isNull();
    assertThat(stored(library, "source_insecure_ssl")).isEqualTo("false");

    Seen seen = run(library);
    assertThat(seen.settings().connectorSettings().asMap())
        .isEqualTo(Map.of("edition", "DC", "topic", "Wetter"));
    assertThat(seen.settings().sourceProxy()).isEqualTo(PROXY);
    assertThat(seen.settings().sourceInsecureSsl()).isTrue();
    assertThat(seen.credentials()).isEqualTo("nutzer:geheim");
  }

  @Test
  void aValueTheProfileSetsOtherwiseIsRefusedOnCreationTestAndChange() throws Exception {
    UUID profile = createProfile("NONE", "{\"edition\": \"DC\"}", PROXY, false);

    mockMvc
        .perform(
            as("dev-user", post("/api/v1/libraries"))
                .content(libraryJson(profile, edition("CLOUD"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("sourceSettings.edition")));
    mockMvc
        .perform(
            as("dev-user", post("/api/v1/libraries"))
                .content(libraryJson(profile, "\"sourceProxy\": \"eigen.example.org:8080\",")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("sourceProxy")));
    mockMvc
        .perform(
            as("dev-user", post("/api/v1/libraries/source-test"))
                .content(
                    """
                    {"sourceType": "PROFILE_PROBE", "connectionProfileId": "%s",
                     "sourceSettings": {"edition": "CLOUD"}}
                    """
                        .formatted(profile)))
        .andExpect(status().isBadRequest());

    UUID library = createLibrary(profile, edition("DC"));
    mockMvc
        .perform(
            as("dev-user", put("/api/v1/libraries/" + library))
                .content("{\"name\": \"Ablage\", " + edition("CLOUD").replaceAll(",$", "") + "}"))
        .andExpect(status().isBadRequest());
    assertThat(stored(library, "source_settings")).isNull();
  }

  /** The audit gap of an own value under a default cannot arise: only real changes are named. */
  @Test
  void aChangeRepeatingTheDefaultsNamesOnlyTheOwnChange() throws Exception {
    UUID profile = createProfile("NONE", "{\"edition\": \"DC\"}", null, false);
    UUID library = createLibrary(profile, "\"sourceSettings\": {\"topic\": \"alt\"},");

    mockMvc
        .perform(
            as("dev-user", put("/api/v1/libraries/" + library))
                .content(
                    "{\"name\": \"Ablage\", \"sourceSettings\": {\"edition\": \"DC\", \"topic\":"
                        + " \"neu\"}}"))
        .andExpect(status().isOk());

    assertThat(stored(library, "source_settings")).doesNotContain("edition").contains("neu");
    List<String> changes =
        jdbc.queryForList(
            "SELECT after::text FROM audit_log WHERE object_id = ? AND event_type = ?",
            String.class,
            library.toString(),
            "LIBRARY_SOURCE_UPDATED");
    assertThat(changes).hasSize(1);
    assertThat(changes.getFirst()).contains("topic").doesNotContain("edition");
  }

  @Test
  void aConnectionTestRunsThroughTheChosenProfile() throws Exception {
    UUID profile = createProfile("PERSONAL_SECRET", "{\"edition\": \"DC\"}", PROXY, false);
    probe.lastTested();

    mockMvc
        .perform(
            as("dev-user", post("/api/v1/libraries/source-test"))
                .content(
                    """
                    {"sourceType": "PROFILE_PROBE", "connectionProfileId": "%s",
                     "sourceCredentials": "nutzer:geheim", "sourceSettings": {"topic": "t"}}
                    """
                        .formatted(profile)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reachable").value(true));

    SourceSettings tested = probe.lastTested().orElseThrow();
    assertThat(tested.sourceUrl()).isEqualTo("https://probe.example.org");
    assertThat(tested.sourceProxy()).isEqualTo(PROXY);
    assertThat(tested.connectorSettings().asMap()).isEqualTo(Map.of("edition", "DC", "topic", "t"));
    assertThat(tested.sourceCredentials()).isEqualTo("nutzer:geheim");
  }

  /** A library its lock or a removed profile blocks is tested against another one, as a draft. */
  @Test
  void aBlockedLibraryIsTestedAgainstAnotherProfile() throws Exception {
    UUID locked = createProfile("PERSONAL_SECRET", "{\"edition\": \"DC\"}", null, false);
    UUID replacement = createProfile("PERSONAL_SECRET", "{\"edition\": \"CLOUD\"}", null, false);
    UUID library = createLibrary(locked, "\"sourceCredentials\": \"nutzer:geheim\",");
    mockMvc
        .perform(as("dev-admin", put(ADMIN + "/" + locked + "/lock")).content("{\"locked\": true}"))
        .andExpect(status().isOk());

    String onItsProfile = "{\"sourceType\": \"PROFILE_PROBE\", \"libraryId\": \"%s\"%s}";
    mockMvc
        .perform(
            as("dev-user", post("/api/v1/libraries/source-test"))
                .content(onItsProfile.formatted(library, "")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SOURCE_LOCKED"));
    probe.lastTested();
    mockMvc
        .perform(
            as("dev-user", post("/api/v1/libraries/source-test"))
                .content(
                    onItsProfile.formatted(
                        library, ", \"connectionProfileId\": \"" + replacement + "\"")))
        .andExpect(status().isOk());
    SourceSettings tested = probe.lastTested().orElseThrow();
    assertThat(tested.connectorSettings().asMap()).isEqualTo(Map.of("edition", "CLOUD"));
    assertThat(tested.sourceCredentials()).isEqualTo("nutzer:geheim");

    mockMvc
        .perform(as("dev-admin", delete(ADMIN + "/" + locked)))
        .andExpect(status().isNoContent());
    profiles.remove(locked);
    mockMvc
        .perform(
            as("dev-user", post("/api/v1/libraries/source-test"))
                .content(
                    onItsProfile.formatted(
                        library, ", \"connectionProfileId\": \"" + replacement + "\"")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reachable").value(true));
  }

  /** A library the profile requirement locks is repaired by testing it on a profile first. */
  @Test
  void aLibraryTheProfileRequirementLocksIsTestedAgainstAProfile() throws Exception {
    UUID profile = createProfile("NONE", null, null, false);
    String body =
        mockMvc
            .perform(
                as("dev-admin", post("/api/v1/libraries"))
                    .content(
                        "{\"name\": \"Eigen "
                            + UUID.randomUUID()
                            + "\", \"sourceType\": \"PROFILE_PROBE\", \"sourceUrl\":"
                            + " \"https://probe.example.org/eigen\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID library = UUID.fromString(JsonPath.read(body, "$.id"));
    libraries.add(library);
    mockMvc
        .perform(
            as("dev-admin", put("/api/v1/admin/connector-types/PROFILE_PROBE/profile-requirement"))
                .content("{\"required\": true, \"ownAddressStock\": \"LOCKED\"}"))
        .andExpect(status().isOk());

    String test = "{\"sourceType\": \"PROFILE_PROBE\", \"libraryId\": \"%s\"%s}";
    mockMvc
        .perform(
            as("dev-admin", post("/api/v1/libraries/source-test"))
                .content(test.formatted(library, "")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SOURCE_LOCKED"));
    mockMvc
        .perform(
            as("dev-admin", post("/api/v1/libraries/source-test"))
                .content(
                    test.formatted(
                        library,
                        ", \"connectionProfileId\": \""
                            + profile
                            + "\", \"sourceUrl\":"
                            + " \"https://probe.example.org/eigen\"")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reachable").value(true));
  }

  @Test
  void aProfileProxyIsHostAndPort() throws Exception {
    mockMvc
        .perform(
            as("dev-admin", post(ADMIN))
                .content(
                    profileJson(
                        "Zugang Proxy " + UUID.randomUUID(), "NONE", null, "kaputt", false)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("host:port")));
    UUID profile = createProfile("NONE", null, PROXY, true);
    mockMvc
        .perform(as("dev-admin", get(ADMIN + "/" + profile)))
        .andExpect(jsonPath("$.sourceProxy").value(PROXY))
        .andExpect(jsonPath("$.sourceInsecureSsl").value(true));
  }

  private UUID createProfile(String method, String defaults, String proxy, boolean insecureSsl)
      throws Exception {
    String body =
        mockMvc
            .perform(
                as("dev-admin", post(ADMIN))
                    .content(
                        profileJson(
                            "Zugang Entwurf " + UUID.randomUUID(),
                            method,
                            defaults,
                            proxy,
                            insecureSsl)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
    profiles.add(id);
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + id);
    return id;
  }

  private UUID createLibrary(UUID profile, String extra) throws Exception {
    String body =
        mockMvc
            .perform(
                as("dev-user", post("/api/v1/libraries"))
                    .content(
                        libraryJson(
                            profile, extra + "\"sourceUrl\": \"https://probe.example.org/x\",")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
    libraries.add(id);
    return id;
  }

  private Seen run(UUID library) throws Exception {
    mockMvc
        .perform(as("dev-user", post("/api/v1/libraries/" + library + "/indexing")))
        .andExpect(status().isAccepted());
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                mockMvc
                    .perform(
                        as("dev-user", get("/api/v1/libraries/" + library + "/indexing/status")))
                    .andExpect(jsonPath("$.status").value("COMPLETED")));
    return probeRun.seenBy(library).orElseThrow();
  }

  private String stored(UUID library, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + "::text FROM knowledge_libraries WHERE id = ?", String.class, library);
  }

  private static String edition(String value) {
    return "\"sourceSettings\": {\"edition\": \"" + value + "\"},";
  }

  private static String libraryJson(UUID profile, String extra) {
    return "{\"name\": \"Bibliothek "
        + UUID.randomUUID()
        + "\", \"sourceType\": \"PROFILE_PROBE\", "
        + extra
        + "\"connectionProfileId\": \""
        + profile
        + "\"}";
  }

  private static String profileJson(
      String name, String method, String defaults, String proxy, boolean insecureSsl) {
    return """
        {"name": "%s", "sourceType": "PROFILE_PROBE", "serverUrl": "https://probe.example.org",
         "authMethod": "%s", "ownership": "LIBRARY", "sourceInsecureSsl": %s%s%s}
        """
        .formatted(
            name,
            method,
            insecureSsl,
            defaults == null ? "" : ", \"connectorSettings\": " + defaults,
            proxy == null ? "" : ", \"sourceProxy\": \"" + proxy + "\"");
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
