package io.opaa.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.indexing.source.profileprobe.PersonProbeSourceConnector;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector.SourceChange;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The preview of a profile change names what saving it discards - stored secrets of libraries,
 * configurations, connected accounts of persons, sync states - and saving does exactly that, for
 * each kind of change: address, sign-in method, a default with and without binding, proxy and TLS
 * switch, ownership. Its confirmation is word for word the question of the 409.
 */
@OpaaIntegrationTest
class ProfileChangeDiscardsIntegrationTest {

  private static final String ADMIN = "/api/v1/admin/connection-profiles";
  private static final String PROBE_SERVER = "https://probe.example.org";
  private static final String PERSON_SERVER = "https://person.example.org";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private ProfileProbeSourceConnector probe;
  @Autowired private PersonProbeSourceConnector personProbe;

  private final List<UUID> profiles = new ArrayList<>();
  private final List<UUID> libraries = new ArrayList<>();

  @BeforeEach
  void forgetWhatTheProbesSaw() throws Exception {
    mockMvc.perform(as("dev-user", get("/api/v1/spaces"))).andExpect(status().isOk());
    probe.sourceChanges();
    personProbe.sourceChanges();
  }

  @AfterEach
  void tearDown() {
    for (UUID library : libraries) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", library.toString());
    }
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    for (UUID profile : profiles) {
      jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
      jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", profile);
      ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
  }

  @Test
  void anAddressChangeNamesTheStoredSecretsAndEveryConfigurationItDiscards() throws Exception {
    String name = "Zugang Adresse " + UUID.randomUUID();
    UUID profile = probeProfile(name, "PERSONAL_SECRET", "");
    probeLibrary(profile, "/a", "a:1");
    probeLibrary(profile, "/b", "b:2");
    probeLibrary(profile, "/c", null);

    Saved saved =
        previewThenSave(
            profile,
            probeChange(name, "https://neu.example.org/probe", "PERSONAL_SECRET", ""),
            true);

    assertThat(saved.secretsDiscarded()).isEqualTo(2);
    assertThat(saved.configurationsChanged()).isEqualTo(3);
    assertThat(saved.connectionsDiscarded()).isEqualTo(3);
    assertThat(saved.preview().get("connectedAccountsEnded")).isNull();
    assertThat(saved.confirmation()).contains("3 Verbindungen", "2 Bibliotheken");
  }

  @Test
  void anotherSignInMethodDiscardsTheStoredSecretsAndChangesNoConfiguration() throws Exception {
    String name = "Zugang Anmeldeart " + UUID.randomUUID();
    UUID profile = probeProfile(name, "PERSONAL_SECRET", "");
    probeLibrary(profile, "/a", "a:1");
    probeLibrary(profile, "/b", null);

    Saved saved = previewThenSave(profile, probeChange(name, PROBE_SERVER, "NONE", ""), true);

    assertThat(saved.secretsDiscarded()).isEqualTo(1);
    assertThat(saved.configurationsChanged()).isZero();
    assertThat(saved.connectionsDiscarded()).isEqualTo(2);
    assertThat(saved.confirmation()).contains("1 Bibliothek");
  }

  @Test
  void aProxyAndTlsChangeKeepsTheSecretsAndAsksNothing() throws Exception {
    String name = "Zugang Proxy " + UUID.randomUUID();
    UUID profile = probeProfile(name, "PERSONAL_SECRET", "");
    probeLibrary(profile, "/a", "a:1");
    probeLibrary(profile, "/b", null);

    Saved saved =
        previewThenSave(
            profile,
            probeChange(
                name,
                PROBE_SERVER,
                "PERSONAL_SECRET",
                ", \"sourceProxy\": \"proxy.example.org:3128\", \"sourceInsecureSsl\": true"),
            false);

    assertThat(saved.secretsDiscarded()).isZero();
    assertThat(saved.configurationsChanged()).isEqualTo(2);
    assertThat(saved.connectionsDiscarded()).isZero();
    assertThat(saved.confirmation()).isNull();
  }

  @Test
  void aDefaultWithoutBindingDiscardsTheSyncStateButNoSecretAndNoAccount() throws Exception {
    String name = "Zugang Bereich " + UUID.randomUUID();
    UUID profile = personProfile(name, "BOTH", "{\"realm\": \"eins\"}");
    personLibrary(profile, "/a", "a:1");
    personLibrary(profile, "/b", null);
    connectAccount(profile);

    Saved saved =
        previewThenSave(profile, personChange(name, "BOTH", "{\"realm\": \"zwei\"}"), true);

    assertThat(saved.secretsDiscarded()).isZero();
    assertThat(saved.configurationsChanged()).isEqualTo(2);
    assertThat(saved.preview().get("connectedAccountsEnded")).isNull();
    assertThat(((Number) saved.preview().get("fullSyncLibraries")).longValue()).isEqualTo(2);
    assertThat(saved.confirmation()).contains("Abgleichsstand von 2 Bibliotheken");
  }

  @Test
  void aDefaultWithBindingDiscardsTheBoundSecretsAndEndsTheAccounts() throws Exception {
    String name = "Zugang Freigabe " + UUID.randomUUID();
    UUID profile = personProfile(name, "BOTH", "{\"share\": \"a\"}");
    personLibrary(profile, "/a", "a:1");
    personLibrary(profile, "/b", null);
    connectAccount(profile);

    Saved saved = previewThenSave(profile, personChange(name, "BOTH", "{\"share\": \"b\"}"), true);

    assertThat(saved.secretsDiscarded()).isEqualTo(1);
    assertThat(saved.configurationsChanged()).isEqualTo(2);
    assertThat(saved.preview().get("connectedAccountsEnded"))
        .isEqualTo(saved.preview().get("connectedAccounts"));
    assertThat(saved.confirmation()).contains("Personen");
  }

  @Test
  void anOwnershipWithoutPersonsEndsTheAccountsAndDiscardsNoLibrarySecret() throws Exception {
    String name = "Zugang Besitzart " + UUID.randomUUID();
    UUID profile = personProfile(name, "BOTH", null);
    personLibrary(profile, "/a", "a:1");
    connectAccount(profile);

    Saved saved = previewThenSave(profile, personChange(name, "LIBRARY", null), true);

    assertThat(saved.secretsDiscarded()).isZero();
    assertThat(saved.configurationsChanged()).isZero();
    assertThat(saved.preview().get("connectedAccountsEnded")).isNotNull();
    assertThat(saved.confirmation()).contains("Personen").doesNotContainPattern("\\d");
  }

  /**
   * Previews {@code change}, saves it - answering the 409 where the preview names a confirmation,
   * whose text must be the 409's - and checks that the preview named what saving did.
   */
  private Saved previewThenSave(UUID profile, String change, boolean asksConfirmation)
      throws Exception {
    Map<UUID, Boolean> heldBefore = heldSecrets();
    long accountsBefore = openAccounts(profile);
    Map<String, Object> preview =
        JsonPath.read(
            body(as("dev-admin", post(ADMIN + "/" + profile + "/impact")).content(change)), "$");
    probe.sourceChanges();
    personProbe.sourceChanges();

    MockHttpServletResponse first =
        mockMvc
            .perform(as("dev-admin", put(ADMIN + "/" + profile)).content(change))
            .andReturn()
            .getResponse();
    String confirmation = (String) preview.get("confirmation");
    if (asksConfirmation) {
      assertThat(first.getStatus()).isEqualTo(409);
      assertThat(
              (String) JsonPath.read(first.getContentAsString(StandardCharsets.UTF_8), "$.error"))
          .isEqualTo(confirmation);
      mockMvc
          .perform(
              as("dev-admin", put(ADMIN + "/" + profile))
                  .content(change.replaceFirst("}\\s*$", ", \"confirmDiscard\": true}")))
          .andExpect(status().isOk());
    } else {
      assertThat(first.getStatus()).isEqualTo(200);
      assertThat(confirmation).isNull();
    }

    long secretsDiscarded =
        heldBefore.entrySet().stream()
            .filter(Map.Entry::getValue)
            .filter(entry -> !heldSecrets().get(entry.getKey()))
            .count();
    Set<UUID> changed = new HashSet<>();
    probe.sourceChanges().stream().map(SourceChange::libraryId).forEach(changed::add);
    changed.addAll(personProbe.sourceChanges());
    changed.retainAll(libraries);
    boolean accountsEnded = accountsBefore > 0 && openAccounts(profile) == 0;

    long previewedSecrets = ((Number) preview.get("secretsDiscarded")).longValue();
    long previewedConfigurations = ((Number) preview.get("configurationsChanged")).longValue();
    assertThat(previewedSecrets).as("secretsDiscarded").isEqualTo(secretsDiscarded);
    assertThat(previewedConfigurations).as("configurationsChanged").isEqualTo(changed.size());
    assertThat(preview.get("connectedAccountsEnded") != null)
        .as("connectedAccountsEnded")
        .isEqualTo(accountsEnded);
    return new Saved(preview, previewedSecrets, previewedConfigurations, confirmation);
  }

  private record Saved(
      Map<String, Object> preview,
      long secretsDiscarded,
      long configurationsChanged,
      String confirmation) {

    long connectionsDiscarded() {
      return ((Number) preview.get("connectionsDiscarded")).longValue();
    }
  }

  private Map<UUID, Boolean> heldSecrets() {
    Map<UUID, Boolean> held = new java.util.HashMap<>();
    for (UUID library : libraries) {
      held.put(
          library,
          jdbc.queryForObject(
              "SELECT source_credentials IS NOT NULL FROM knowledge_libraries WHERE id = ?",
              Boolean.class,
              library));
    }
    return held;
  }

  private long openAccounts(UUID profile) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM connected_accounts WHERE profile_id = ? AND state <> 'DISCONNECTED'",
        Long.class,
        profile);
  }

  private void connectAccount(UUID profile) throws Exception {
    mockMvc
        .perform(
            as("dev-user", put("/api/v1/me/connected-accounts/" + profile))
                .content(
                    "{\"username\": \"avogt\", \"secret\": \""
                        + PersonProbeSourceConnector.ACCEPTED_PASSWORD
                        + "\"}"))
        .andExpect(status().isOk());
  }

  private UUID probeProfile(String name, String method, String extra) throws Exception {
    return created(
        post(ADMIN),
        """
        {"name": "%s", "sourceType": "PROFILE_PROBE", "serverUrl": "%s", "authMethod": "%s",
         "ownership": "LIBRARY"%s}
        """
            .formatted(name, PROBE_SERVER, method, extra),
        profiles);
  }

  private static String probeChange(String name, String server, String method, String extra) {
    return """
        {"name": "%s", "serverUrl": "%s", "authMethod": "%s", "ownership": "LIBRARY"%s}
        """
        .formatted(name, server, method, extra);
  }

  private UUID personProfile(String name, String ownership, String defaults) throws Exception {
    return created(
        post(ADMIN),
        """
        {"name": "%s", "sourceType": "PERSON_PROBE", "serverUrl": "%s",
         "authMethod": "PERSONAL_SECRET", "ownership": "%s"%s}
        """
            .formatted(
                name,
                PERSON_SERVER,
                ownership,
                defaults == null ? "" : ", \"connectorSettings\": " + defaults),
        profiles);
  }

  private static String personChange(String name, String ownership, String defaults) {
    return """
        {"name": "%s", "serverUrl": "%s", "authMethod": "PERSONAL_SECRET", "ownership": "%s"%s}
        """
        .formatted(
            name,
            PERSON_SERVER,
            ownership,
            defaults == null ? "" : ", \"connectorSettings\": " + defaults);
  }

  private void probeLibrary(UUID profile, String path, String credentials) throws Exception {
    library("PROFILE_PROBE", profile, PROBE_SERVER + path, credentials);
  }

  private void personLibrary(UUID profile, String path, String credentials) throws Exception {
    library("PERSON_PROBE", profile, PERSON_SERVER + path, credentials);
  }

  private void library(String type, UUID profile, String url, String credentials) throws Exception {
    created(
        post("/api/v1/libraries"),
        """
        {"name": "Bibliothek %s", "sourceType": "%s", "sourceUrl": "%s", %s
         "connectionProfileId": "%s"}
        """
            .formatted(
                UUID.randomUUID(),
                type,
                url,
                credentials == null ? "" : "\"sourceCredentials\": \"" + credentials + "\",",
                profile),
        libraries);
  }

  private UUID created(MockHttpServletRequestBuilder request, String json, List<UUID> into)
      throws Exception {
    boolean profile = into == profiles;
    String body =
        mockMvc
            .perform(as(profile ? "dev-admin" : "dev-user", request).content(json))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
    into.add(id);
    if (profile) {
      ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + id);
    }
    return id;
  }

  private String body(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc
        .perform(request)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
