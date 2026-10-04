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
import io.opaa.indexing.source.profileprobe.ProfileProbeIndexingExecutor;
import io.opaa.indexing.source.profileprobe.ProfileProbeIndexingExecutor.Seen;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Connection profiles through the API (#2160) with the test-only {@code PROFILE_PROBE} connector:
 * administration, connected libraries, the port's answer in a run, and the discard of every secret
 * on an address change, the emergency shutdown and the deletion.
 */
@OpaaIntegrationTest
class ConnectionProfileApiIntegrationTest {

  private static final String ADMIN = "/api/v1/admin/connection-profiles";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private ProfileProbeIndexingExecutor probe;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private TransactionTemplate transactions;

  private final List<UUID> profiles = new ArrayList<>();
  private final List<UUID> libraries = new ArrayList<>();
  private final List<String> releasedScopes = new ArrayList<>();

  @AfterEach
  void tearDown() {
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    for (String scope : releasedScopes) {
      ConnectorReleases.withdraw(jdbc, scope);
    }
    for (UUID profile : profiles) {
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
  }

  @Test
  void theDescriptorReportsTheProfileDeclaration() throws Exception {
    String probe = "$[?(@.type == 'PROFILE_PROBE')]";
    mockMvc
        .perform(as("dev-user", get("/api/v1/source-types")))
        .andExpect(status().isOk())
        .andExpect(jsonPath(probe + ".profileSupport").value("OPTIONAL"))
        .andExpect(
            jsonPath(probe + ".signIns[*].method")
                .value(org.hamcrest.Matchers.containsInAnyOrder("NONE", "PERSONAL_SECRET")))
        .andExpect(
            jsonPath(probe + ".signIns[?(@.method == 'NONE')].ownerships[*]")
                .value(org.hamcrest.Matchers.containsInAnyOrder("LIBRARY", "PERSON")))
        .andExpect(
            jsonPath(probe + ".signIns[?(@.method == 'PERSONAL_SECRET')].ownerships[*]")
                .value(org.hamcrest.Matchers.contains("LIBRARY")))
        .andExpect(
            jsonPath(probe + ".signIns[?(@.method == 'PERSONAL_SECRET')].secretForm")
                .value("USERNAME_AND_PASSWORD"))
        .andExpect(
            jsonPath(probe + ".signIns[?(@.method == 'NONE')].secretForm")
                .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())))
        .andExpect(jsonPath(probe + ".profileDefaults[*].key").value("edition"))
        .andExpect(jsonPath(probe + ".profileDefaults[*].kind").value("CHOICE"))
        .andExpect(
            jsonPath(probe + ".profileDefaults[*].choices[*]")
                .value(org.hamcrest.Matchers.contains("CLOUD", "DC")))
        .andExpect(
            jsonPath(probe + ".serverAddress.schemes[*]")
                .value(org.hamcrest.Matchers.contains("https", "http", "smb")))
        .andExpect(
            jsonPath("$[?(@.type == 'PROFILE_OAUTH_PROBE')].profileSupport").value("REQUIRED"))
        .andExpect(jsonPath("$[?(@.type == 'UPLOAD')].profileSupport").value("FORBIDDEN"))
        .andExpect(jsonPath("$[?(@.type == 'UPLOAD')].signIns[*]").isEmpty())
        .andExpect(jsonPath("$[?(@.type == 'UPLOAD')].profileDefaults[*]").isEmpty());
  }

  @Test
  void onlyTheSystemAdministrationManagesProfiles() throws Exception {
    mockMvc
        .perform(as("dev-user", post(ADMIN)).content(profileJson("Zugang Nutzer", "NONE", null)))
        .andExpect(status().isForbidden());
    mockMvc.perform(as("dev-user", get(ADMIN))).andExpect(status().isForbidden());
  }

  /** The ownership of a profile lies in the owners of its sign-in, else 400. */
  @Test
  void aProfileTakesOnlyWhatItsConnectorOffers() throws Exception {
    mockMvc
        .perform(
            as("dev-admin", post(ADMIN))
                .content(profileJson("Zugang ohne Angebot", "CLIENT_CREDENTIALS", null)))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error").value(org.hamcrest.Matchers.containsString("nicht angeboten")));
    mockMvc
        .perform(
            as("dev-admin", post(ADMIN))
                .content(
                    """
                    {"name": "Zugang Testquelle", "sourceType": "PROBE", "serverUrl":
                     "https://probe.example.org", "authMethod": "NONE", "ownership": "LIBRARY"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error").value(org.hamcrest.Matchers.containsString("keine Zugänge")));
    mockMvc
        .perform(
            as("dev-admin", post(ADMIN))
                .content(
                    """
                    {"name": "Zugang Person", "sourceType": "PROFILE_PROBE", "serverUrl":
                     "https://probe.example.org", "authMethod": "PERSONAL_SECRET",
                     "ownership": "PERSON"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Besitzart")));
  }

  /** Acceptance criterion: a profile with an undeclared default key is refused with 400. */
  @Test
  void aProfileSetsOnlyTheDeclaredDefaultsEachOfItsKind() throws Exception {
    mockMvc
        .perform(
            as("dev-admin", post(ADMIN))
                .content(profileJson("Zugang Fremdfeld", "NONE", "{\"spaces\": []}")))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error")
                .value("connectorSettings: das Feld spaces kann ein Zugang nicht vorgeben"));
    // topic is a settings key of the connector, but no profile default
    mockMvc
        .perform(
            as("dev-admin", post(ADMIN))
                .content(profileJson("Zugang Thema", "NONE", "{\"topic\": \"Wetter\"}")))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error")
                .value("connectorSettings: das Feld topic kann ein Zugang nicht vorgeben"));
    mockMvc
        .perform(
            as("dev-admin", post(ADMIN))
                .content(profileJson("Zugang Edition", "NONE", "{\"edition\": \"SERVER\"}")))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error")
                .value("connectorSettings.edition muss einer der Werte CLOUD, DC sein"));
  }

  @Test
  void theServerAddressTakesTheSchemesTheConnectorDeclares() throws Exception {
    String smb =
        """
        {"name": "Zugang Freigabe %s", "sourceType": "PROFILE_PROBE", "serverUrl":
         "SMB://Fileserver.example.org/Ablage/", "authMethod": "NONE", "ownership": "LIBRARY"}
        """
            .formatted(UUID.randomUUID());
    String body =
        mockMvc
            .perform(as("dev-admin", post(ADMIN)).content(smb))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.serverUrl").value("smb://fileserver.example.org/Ablage"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    profiles.add(UUID.fromString(JsonPath.read(body, "$.id")));

    mockMvc
        .perform(
            as("dev-admin", post(ADMIN))
                .content(smb.replace("SMB://", "ftp://").replace("Freigabe", "FTP")))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error")
                .value(org.hamcrest.Matchers.containsString("https://, http://, smb://")));
  }

  @Test
  void aLibraryOnAProfileRunsWithTheProfileDefaultsAndItsOwnSecret() throws Exception {
    UUID profile =
        createProfile(
            profileJson(
                "Zugang Lauf " + UUID.randomUUID(), "PERSONAL_SECRET", "{\"edition\": \"DC\"}"));
    UUID library =
        createLibrary(
            profile,
            "https://probe.example.org/ablage",
            "\"sourceCredentials\": \"nutzer:geheim\", \"sourceSettings\": {\"topic\":"
                + " \"Wetter\"},");

    mockMvc
        .perform(as("dev-user", get("/api/v1/libraries/" + library)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connectionProfile.id").value(profile.toString()))
        .andExpect(jsonPath("$.connectionProfileRemoved").value(false));

    Seen seen = run(library, "COMPLETED");
    assertThat(seen.credentials()).isEqualTo("nutzer:geheim");
    assertThat(seen.settings().connectorSettings().asMap())
        .isEqualTo(Map.of("edition", "DC", "topic", "Wetter"));
    assertThat(seen.settings().sourceUrl()).isEqualTo("https://probe.example.org/ablage");
  }

  @Test
  void anAddressOutsideTheProfileIsRefused() throws Exception {
    UUID profile = createProfile(profileJson("Zugang Grenze " + UUID.randomUUID(), "NONE", null));

    mockMvc
        .perform(
            as("dev-user", post("/api/v1/libraries"))
                .content(
                    """
                    {"name": "Fremd", "sourceType": "PROFILE_PROBE", "connectionProfileId": "%s",
                     "sourceUrl": "https://fremd.example.org/ablage"}
                    """
                        .formatted(profile)))
        .andExpect(status().isBadRequest());
    UUID library = createLibrary(profile, null, "");
    mockMvc
        .perform(
            as("dev-user", put("/api/v1/libraries/" + library))
                .content(
                    "{\"name\": \"Bibliothek\", \"sourceUrl\": \"https://fremd.example.org\"}"))
        .andExpect(status().isBadRequest());
    assertThat(storedUrl(library)).isEqualTo("https://probe.example.org");
  }

  /** Acceptance criterion: an address change discards every secret of the profile. */
  @Test
  void anAddressChangeNeedsAConfirmationAndDiscardsEverySecretOfTheProfile() throws Exception {
    String name = "Zugang Umzug " + UUID.randomUUID();
    UUID profile = createProfile(profileJson(name, "PERSONAL_SECRET", null));
    UUID first =
        createLibrary(profile, "https://probe.example.org/a", "\"sourceCredentials\": \"a:1\",");
    UUID second =
        createLibrary(profile, "https://probe.example.org/b/c", "\"sourceCredentials\": \"b:2\",");
    assertThat(storedCredentials(first)).isNotNull();

    mockMvc
        .perform(as("dev-admin", get(ADMIN + "/" + profile + "/impact")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connections").value(2))
        .andExpect(jsonPath("$.libraries").value(2));
    String moved =
        """
        {"name": "%s", "serverUrl": "https://neu.example.org/probe", "authMethod":
         "PERSONAL_SECRET", "ownership": "LIBRARY"%s}
        """;
    mockMvc
        .perform(as("dev-admin", put(ADMIN + "/" + profile)).content(moved.formatted(name, "")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONNECTION_PROFILE_CONFIRMATION_REQUIRED"));
    assertThat(storedCredentials(first)).isNotNull();

    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + profile))
                .content(moved.formatted(name, ", \"confirmDiscard\": true")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.serverUrl").value("https://neu.example.org/probe"));

    assertThat(storedCredentials(first)).isNull();
    assertThat(storedCredentials(second)).isNull();
    assertThat(storedUrl(first)).isEqualTo("https://neu.example.org/probe/a");
    assertThat(storedUrl(second)).isEqualTo("https://neu.example.org/probe/b/c");
    assertThat(run(first, "FAILED")).isNull();
    assertThat(lastRunMessage(first)).contains("Verbindung getrennt:");
  }

  /** Acceptance criterion: the emergency shutdown deletes every secret of the profile. */
  @Test
  void theEmergencyShutdownDiscardsEverySecretAndKeepsTheProfile() throws Exception {
    UUID profile =
        createProfile(profileJson("Zugang Notaus " + UUID.randomUUID(), "PERSONAL_SECRET", null));
    UUID library = createLibrary(profile, null, "\"sourceCredentials\": \"x:y\",");

    mockMvc
        .perform(as("dev-admin", post(ADMIN + "/" + profile + "/disconnect-all")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connections").value(1));

    assertThat(storedCredentials(library)).isNull();
    mockMvc.perform(as("dev-admin", get(ADMIN + "/" + profile))).andExpect(status().isOk());
    assertThat(auditTypes(profile)).contains("CONNECTION_PROFILE_DISCONNECTED");
  }

  @Test
  void aNewSecretForTheSameClientKeepsTheConnectionsANewClientIdNeedsConfirmation()
      throws Exception {
    String name = "Zugang OAuth " + UUID.randomUUID();
    String json =
        """
        {"name": "%s", "sourceType": "PROFILE_OAUTH_PROBE", "serverUrl":
         "https://probe.example.org", "authMethod": "OAUTH", "ownership": "BOTH",
         "clientId": "%s", "clientSecret": "%s"}
        """;
    UUID profile = createProfile(json.formatted(name, "opaa", "alt"));
    UUID library = createLibrary("PROFILE_OAUTH_PROBE", profile, null, "");
    // no request may set a secret under OAuth; the stored one stands for any held under the profile
    transactions.executeWithoutResult(
        status -> {
          KnowledgeLibrary row = libraryRepository.findById(library).orElseThrow();
          row.updateSourceConfiguration(
              row.getSourcePath(), row.getSourceUrl(), null, "bleibt", false);
          libraryRepository.save(row);
        });

    String update =
        """
        {"name": "%s", "serverUrl": "https://probe.example.org", "authMethod": "OAUTH",
         "ownership": "BOTH", "clientId": "%s", "clientSecret": "neu"}
        """;
    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + profile)).content(update.formatted(name, "opaa")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.clientSecretSet").value(true));
    assertThat(storedCredentials(library)).isNotNull();

    mockMvc
        .perform(as("dev-admin", put(ADMIN + "/" + profile)).content(update.formatted(name, "neu")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONNECTION_PROFILE_CONFIRMATION_REQUIRED"));
  }

  @Test
  void aDeletedProfileLeavesItsLibrariesWithoutRunsUntilTheyAreConnectedAgain() throws Exception {
    UUID profile =
        createProfile(profileJson("Zugang alt " + UUID.randomUUID(), "PERSONAL_SECRET", null));
    UUID replacement = createProfile(profileJson("Zugang neu " + UUID.randomUUID(), "NONE", null));
    UUID library =
        createLibrary(profile, "https://probe.example.org/x", "\"sourceCredentials\": \"s:t\",");

    mockMvc
        .perform(as("dev-admin", delete(ADMIN + "/" + profile)))
        .andExpect(status().isNoContent());
    profiles.remove(profile);

    mockMvc
        .perform(as("dev-user", get("/api/v1/libraries/" + library)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connectionProfileRemoved").value(true))
        .andExpect(jsonPath("$.connectionProfile").doesNotExist());
    assertThat(storedCredentials(library)).isNull();
    assertThat(run(library, "FAILED")).isNull();
    assertThat(lastRunMessage(library)).contains("Zugang entfernt:");
    // a rename resolves nothing and is not stopped by the removed profile
    mockMvc
        .perform(as("dev-user", put("/api/v1/libraries/" + library)).content("{\"name\": \"Neu\"}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            as("dev-user", put("/api/v1/libraries/" + library + "/connection-profile"))
                .content("{\"profileId\": \"" + replacement + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connectionProfile.id").value(replacement.toString()))
        .andExpect(jsonPath("$.connectionProfileRemoved").value(false));
    assertThat(run(library, "COMPLETED").credentials()).isNull();

    // an own address is the type's target, released here like the delivered types
    releasedScopes.add("TYPE:PROFILE_PROBE");
    ConnectorReleases.releaseToAllAccounts(jdbc, "TYPE:PROFILE_PROBE");
    mockMvc
        .perform(as("dev-user", delete("/api/v1/libraries/" + library + "/connection-profile")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connectionProfile").doesNotExist());
  }

  /** Connecting and releasing need MANAGER on the library; a reader is refused. */
  @Test
  void aReaderOfTheLibraryNeitherConnectsNorReleasesIt() throws Exception {
    UUID profile = createProfile(profileJson("Zugang Rechte " + UUID.randomUUID(), "NONE", null));
    String body =
        mockMvc
            .perform(
                as("dev-admin", post("/api/v1/libraries"))
                    .content(
                        "{\"name\": \"Fremd "
                            + UUID.randomUUID()
                            + "\", \"sourceType\": \"PROFILE_PROBE\", \"connectionProfileId\": \""
                            + profile
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID library = UUID.fromString(JsonPath.read(body, "$.id"));
    libraries.add(library);
    jdbc.update(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type, role)"
            + " SELECT gen_random_uuid(), 'KNOWLEDGE_LIBRARY', id, organization_id, 'ALL_ACCOUNTS',"
            + " 'VIEWER' FROM knowledge_libraries WHERE id = ?",
        library);

    mockMvc
        .perform(
            as("dev-user", put("/api/v1/libraries/" + library + "/connection-profile"))
                .content("{\"profileId\": \"" + profile + "\"}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(as("dev-user", delete("/api/v1/libraries/" + library + "/connection-profile")))
        .andExpect(status().isForbidden());
  }

  @Test
  void theSelectionListsOnlyProfilesThatAdmitLibraries() throws Exception {
    String suffix = UUID.randomUUID().toString();
    UUID forLibraries = createProfile(profileJson("Zugang Bib " + suffix, "NONE", null));
    UUID forPersons =
        createProfile(
            """
            {"name": "Zugang Person %s", "sourceType": "PROFILE_PROBE", "serverUrl":
             "https://probe.example.org", "authMethod": "NONE", "ownership": "PERSON"}
            """
                .formatted(suffix));

    String body =
        mockMvc
            .perform(as("dev-user", get("/api/v1/connection-profiles?sourceType=PROFILE_PROBE")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    List<String> ids = JsonPath.read(body, "$[*].id");
    assertThat(ids).contains(forLibraries.toString()).doesNotContain(forPersons.toString());
    mockMvc
        .perform(
            as(
                    "dev-user",
                    put(
                        "/api/v1/libraries/"
                            + createLibrary(forLibraries, null, "")
                            + "/connection-profile"))
                .content("{\"profileId\": \"" + forPersons + "\"}"))
        .andExpect(status().isBadRequest());
  }

  private UUID createProfile(String json) throws Exception {
    String body =
        mockMvc
            .perform(as("dev-admin", post(ADMIN)).content(json))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
    profiles.add(id);
    // a new profile is off; this class is about what a released one does
    releasedScopes.add("PROFILE:" + id);
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + id);
    return id;
  }

  private UUID createLibrary(UUID profile, String url, String extra) throws Exception {
    return createLibrary("PROFILE_PROBE", profile, url, extra);
  }

  private UUID createLibrary(String sourceType, UUID profile, String url, String extra)
      throws Exception {
    String body =
        mockMvc
            .perform(
                as("dev-user", post("/api/v1/libraries"))
                    .content(
                        "{\"name\": \"Bibliothek "
                            + UUID.randomUUID()
                            + "\", \"sourceType\": \""
                            + sourceType
                            + "\", "
                            + extra
                            + (url == null ? "" : "\"sourceUrl\": \"" + url + "\", ")
                            + "\"connectionProfileId\": \""
                            + profile
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
    libraries.add(id);
    return id;
  }

  /** Runs the library to {@code expectedStatus}; what its body saw, {@code null} for none. */
  private Seen run(UUID library, String expectedStatus) throws Exception {
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
    return probe.seenBy(library).orElse(null);
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

  private String storedCredentials(UUID library) {
    return jdbc.queryForObject(
        "SELECT source_credentials FROM knowledge_libraries WHERE id = ?", String.class, library);
  }

  private String storedUrl(UUID library) {
    return jdbc.queryForObject(
        "SELECT source_url FROM knowledge_libraries WHERE id = ?", String.class, library);
  }

  private List<String> auditTypes(UUID profile) {
    return jdbc.queryForList(
        "SELECT event_type FROM audit_log WHERE object_id = ?", String.class, profile.toString());
  }

  private static String profileJson(String name, String method, String settings) {
    return """
        {"name": "%s", "sourceType": "PROFILE_PROBE", "serverUrl": "https://probe.example.org/",
         "authMethod": "%s", "ownership": "LIBRARY"%s}
        """
        .formatted(name, method, settings == null ? "" : ", \"connectorSettings\": " + settings);
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
