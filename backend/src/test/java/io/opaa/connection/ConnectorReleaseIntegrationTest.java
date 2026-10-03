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
import io.opaa.organization.Organization;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * The connector release and the lock through the API (#2161, spec "Konnektor-Freigabe und Sperre"):
 * a release per profile or type governs only a new library, a lock stops the runs, and a connector
 * or profile the installation did not ship with is off.
 */
@OpaaIntegrationTest
class ConnectorReleaseIntegrationTest {

  private static final String PROFILES = "/api/v1/admin/connection-profiles";
  private static final String GRANTS = "/api/v1/admin/capabilities/CREATE_CONNECTOR_LIBRARY/grants";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures libraryFixtures;

  private final List<UUID> profiles = new ArrayList<>();
  private final List<UUID> libraries = new ArrayList<>();
  private List<UUID> foreignHistoryIds = List.of();

  @BeforeEach
  void rememberForeignHistory() {
    foreignHistoryIds = historyIds();
  }

  @AfterEach
  void tearDown() {
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    for (UUID profile : profiles) {
      ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
    jdbc.update("DELETE FROM connector_type_policies WHERE source_type = 'PROFILE_PROBE'");
    List<UUID> own = new ArrayList<>(historyIds());
    own.removeAll(foreignHistoryIds);
    if (!own.isEmpty()) {
      jdbc.update(
          "DELETE FROM capability_grant_history WHERE id = ANY(CAST(? AS uuid[]))",
          own.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}")));
    }
  }

  /** Acceptance criterion: releasing profile A does not open profile B of the same connector. */
  @Test
  void releasingOneProfileNeverOpensAnotherOfTheSameConnector() throws Exception {
    UUID released = createProfile("Zugang A " + UUID.randomUUID());
    UUID other = createProfile("Zugang B " + UUID.randomUUID());
    grantToAllAccounts("PROFILE:" + released);

    createLibrary("dev-user", released);
    mockMvc
        .perform(as("dev-user", post("/api/v1/libraries")).content(libraryJson(other)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"))
        .andExpect(jsonPath("$.error").value(Matchers.containsString("den Zugang „Zugang B")));

    mockMvc
        .perform(as("dev-user", get("/api/v1/connection-profiles?sourceType=PROFILE_PROBE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + released + "')].creatable").value(true))
        .andExpect(jsonPath("$[?(@.id == '" + other + "')].creatable").value(false))
        .andExpect(
            jsonPath("$[?(@.id == '" + other + "')].creationNotice")
                .value(Matchers.hasItem(Matchers.containsString("Systemverwaltung"))));
  }

  /**
   * Acceptance criteria: a connector the installation did not ship with, and a new profile, are
   * usable by nobody but the system administration until released; /source-types and /me say what
   * the person may create.
   */
  @Test
  void whatTheInstallationDidNotShipIsOffForEveryoneButTheSystemAdministration() throws Exception {
    UUID fresh = createProfile("Zugang neu " + UUID.randomUUID());

    mockMvc
        .perform(as("dev-user", post("/api/v1/libraries")).content(libraryJson(fresh)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));
    mockMvc
        .perform(
            as("dev-user", post("/api/v1/libraries"))
                .content(
                    "{\"name\": \"Probe\", \"sourceType\": \"PROBE\","
                        + " \"sourceSettings\": {\"topic\": \"Wetter\"}}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));
    mockMvc
        .perform(as("dev-user", get("/api/v1/source-types")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.type == 'PROBE')].creatable").value(false))
        .andExpect(
            jsonPath("$[?(@.type == 'PROBE')].creationNotice")
                .value(Matchers.hasItem(Matchers.containsString("Quellart „Testquelle“"))))
        .andExpect(jsonPath("$[?(@.type == 'PROFILE_PROBE')].creatable").value(false))
        .andExpect(jsonPath("$[?(@.type == 'RSS_FEED')].creatable").value(true))
        .andExpect(jsonPath("$[?(@.type == 'RSS_FEED')].creatableWithOwnAddress").value(true))
        .andExpect(jsonPath("$[?(@.type == 'UPLOAD')].creatable").value(true));

    createLibrary("dev-admin", fresh);
  }

  /**
   * /me names the capability while the person holds it in any scope; the type then answers whether
   * a profile or its own address opens it.
   */
  @Test
  void aReleaseOfOnlyAProfileStillNamesTheCapabilityAndOpensTheTypeThroughIt() throws Exception {
    UUID profile = createProfile("Zugang einzig " + UUID.randomUUID());
    grantToAllAccounts("PROFILE:" + profile);
    for (String type : List.of("FILESYSTEM", "HTTP_DIRECTORY", "RSS_FEED", "CONFLUENCE", "S3")) {
      revokeAllAccounts("TYPE:" + type);
    }

    mockMvc
        .perform(as("dev-user", get("/api/v1/me/capabilities")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.capabilities").value(Matchers.hasItem("CREATE_CONNECTOR_LIBRARY")));
    mockMvc
        .perform(as("dev-user", get("/api/v1/source-types")))
        .andExpect(jsonPath("$[?(@.type == 'RSS_FEED')].creatable").value(false))
        .andExpect(jsonPath("$[?(@.type == 'PROFILE_PROBE')].creatable").value(true))
        .andExpect(
            jsonPath("$[?(@.type == 'PROFILE_PROBE')].creatableWithOwnAddress").value(false));
    mockMvc
        .perform(
            as("dev-user", post("/api/v1/libraries"))
                .content(
                    "{\"name\": \"Feed\", \"sourceType\": \"RSS_FEED\","
                        + " \"sourceUrl\": \"https://feeds.example.org/a.xml\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("Quellart „RSS-Feed“")));
  }

  /**
   * Acceptance criterion: a withdrawal lets the existing library run on, a lock stops it; lifting
   * the lock lets it run again without being set up anew. Both locks are governance events.
   */
  @Test
  void aWithdrawalLeavesTheRunsGoingAndALockStopsThem() throws Exception {
    UUID profile = createProfile("Zugang Lauf " + UUID.randomUUID());
    UUID grant = grantToAllAccounts("PROFILE:" + profile);
    UUID library = createLibrary("dev-user", profile);

    mockMvc
        .perform(as("dev-admin", delete(GRANTS + "/" + grant)))
        .andExpect(status().isNoContent());
    run(library, "COMPLETED");

    lockProfile(profile, true);
    run(library, "FAILED");
    assertThat(lastRunMessage(library)).contains("Gesperrt – Inhalt wird nicht mehr aktualisiert");
    mockMvc
        .perform(as("dev-user", get("/api/v1/libraries/" + library)))
        .andExpect(
            jsonPath("$.sourceLockNotice").value(Matchers.containsString("Zugang „Zugang Lauf")));
    mockMvc
        .perform(as("dev-user", get("/api/v1/libraries")))
        .andExpect(
            jsonPath("$[?(@.id == '" + library + "')].sourceLockNotice")
                .value(Matchers.hasItem(Matchers.startsWith("Gesperrt"))));
    mockMvc
        .perform(as("dev-admin", post("/api/v1/libraries")).content(libraryJson(profile)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CONNECTOR_LOCKED"));

    lockProfile(profile, false);
    run(library, "COMPLETED");
    mockMvc
        .perform(as("dev-user", get("/api/v1/libraries/" + library)))
        .andExpect(jsonPath("$.sourceLockNotice").doesNotExist());

    mockMvc
        .perform(
            as("dev-admin", put("/api/v1/admin/connector-types/PROFILE_PROBE/lock"))
                .content("{\"locked\": true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.locked").value(true));
    run(library, "FAILED");
    assertThat(lastRunMessage(library)).contains("Quellart „Testquelle mit Zugang“");
    mockMvc
        .perform(as("dev-user", get("/api/v1/source-types")))
        .andExpect(jsonPath("$[?(@.type == 'PROFILE_PROBE')].locked").value(true));
    mockMvc
        .perform(
            as("dev-admin", put("/api/v1/admin/connector-types/PROFILE_PROBE/lock"))
                .content("{\"locked\": false}"))
        .andExpect(status().isOk());
    run(library, "COMPLETED");

    assertThat(auditTypes(profile)).contains("CONNECTOR_LOCKED", "CONNECTOR_UNLOCKED");
  }

  /** Deleting a profile withdraws its releases, so a later profile starts off. */
  @Test
  void deletingAProfileWithdrawsItsReleases() throws Exception {
    UUID profile = createProfile("Zugang weg " + UUID.randomUUID());
    grantToAllAccounts("PROFILE:" + profile);

    mockMvc
        .perform(as("dev-admin", delete(PROFILES + "/" + profile)))
        .andExpect(status().isNoContent());

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM capability_grants WHERE scope = ?",
                Long.class,
                "PROFILE:" + profile))
        .isZero();
  }

  @Test
  void onlyTheSystemAdministrationLocks() throws Exception {
    UUID profile = createProfile("Zugang fremd " + UUID.randomUUID());
    mockMvc
        .perform(
            as("dev-user", put(PROFILES + "/" + profile + "/lock")).content("{\"locked\": true}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            as("dev-user", put("/api/v1/admin/connector-types/RSS_FEED/lock"))
                .content("{\"locked\": true}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            as("dev-admin", put("/api/v1/admin/connector-types/UPLOAD/lock"))
                .content("{\"locked\": true}"))
        .andExpect(status().isBadRequest());
  }

  private UUID createProfile(String name) throws Exception {
    String body =
        mockMvc
            .perform(
                as("dev-admin", post(PROFILES))
                    .content(
                        """
                        {"name": "%s", "sourceType": "PROFILE_PROBE",
                         "serverUrl": "https://probe.example.org", "authMethod": "NONE",
                         "ownership": "LIBRARY"}
                        """
                            .formatted(name)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.locked").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
    profiles.add(id);
    return id;
  }

  private UUID grantToAllAccounts(String scope) throws Exception {
    String body =
        mockMvc
            .perform(
                as("dev-admin", post(GRANTS))
                    .content("{\"subjectType\": \"ALL_ACCOUNTS\", \"scope\": \"" + scope + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.scope").value(scope))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return UUID.fromString(JsonPath.read(body, "$.id"));
  }

  private void revokeAllAccounts(String scope) throws Exception {
    UUID grant =
        jdbc.queryForObject(
            "SELECT id FROM capability_grants WHERE organization_id = ? AND scope = ?"
                + " AND subject_type = 'ALL_ACCOUNTS'",
            UUID.class,
            Organization.DEFAULT_ID,
            scope);
    mockMvc
        .perform(as("dev-admin", delete(GRANTS + "/" + grant)))
        .andExpect(status().isNoContent());
  }

  private void lockProfile(UUID profile, boolean locked) throws Exception {
    mockMvc
        .perform(
            as("dev-admin", put(PROFILES + "/" + profile + "/lock"))
                .content("{\"locked\": " + locked + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.locked").value(locked));
  }

  private UUID createLibrary(String user, UUID profile) throws Exception {
    String body =
        mockMvc
            .perform(as(user, post("/api/v1/libraries")).content(libraryJson(profile)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
    libraries.add(id);
    return id;
  }

  private void run(UUID library, String expectedStatus) throws Exception {
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
                    .andExpect(jsonPath("$.status").value(expectedStatus)));
  }

  private String lastRunMessage(UUID library) throws Exception {
    String body =
        mockMvc
            .perform(as("dev-user", get("/api/v1/libraries/" + library + "/indexing/status")))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return JsonPath.read(body, "$.message");
  }

  private List<String> auditTypes(UUID profile) {
    return jdbc.queryForList(
        "SELECT event_type FROM audit_log WHERE object_id = ?", String.class, profile.toString());
  }

  private List<UUID> historyIds() {
    return jdbc.queryForList("SELECT id FROM capability_grant_history", UUID.class);
  }

  private static String libraryJson(UUID profile) {
    return "{\"name\": \"Bibliothek "
        + UUID.randomUUID()
        + "\", \"sourceType\": \"PROFILE_PROBE\", \"connectionProfileId\": \""
        + profile
        + "\"}";
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
