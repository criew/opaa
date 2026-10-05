package io.opaa.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetShellService;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.connection.account.ConnectedAccountService;
import io.opaa.indexing.source.profileprobe.PersonProbeSourceConnector;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector;
import io.opaa.indexing.source.profileprobe.ProfileProbeSourceConnector.SourceChange;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.permission.GroupSizeProperties;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The preview of a profile change names what saving it discards - stored secrets of libraries,
 * configurations, connected accounts of persons, sync states - and saving does exactly that, for
 * each kind of change: address, sign-in method, a default with and without binding, proxy and TLS
 * switch, ownership, registration. Its confirmation is word for word the question of the 409, and
 * the private libraries it releases are the ones saving releases, as masked as every other number.
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
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetShellService shellService;
  @Autowired private TransactionTemplate transactions;
  @Autowired private GroupSizeProperties groupSize;
  @Autowired private ConnectedAccountService accounts;

  private final List<UUID> profiles = new ArrayList<>();
  private final List<UUID> libraries = new ArrayList<>();
  private final List<UUID> privateLibraries = new ArrayList<>();
  private final List<UUID> owners = new ArrayList<>();
  private final Map<UUID, UUID> privateOwners = new java.util.HashMap<>();

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
    for (UUID library : privateLibraries) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", library.toString());
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", library);
      jdbc.update("DELETE FROM asset_grant_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM asset_ownership_history WHERE asset_id = ?", library);
    }
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    libraryFixtures.removeLibraries(privateLibraries.toArray(UUID[]::new));
    for (UUID profile : profiles) {
      jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
      jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", profile);
      ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
    for (UUID owner : owners) {
      jdbc.update("DELETE FROM notifications WHERE recipient_user_id = ?", owner);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", owner.toString());
      jdbc.update("DELETE FROM users WHERE id = ?", owner);
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

  @Test
  void aNewClientIdDiscardsEveryConnectionOfTheProfile() throws Exception {
    String name = "Zugang Client " + UUID.randomUUID();
    UUID profile =
        created(
            post(ADMIN),
            """
            {"name": "%s", "sourceType": "PROFILE_OAUTH_PROBE", "serverUrl": "%s",
             "authMethod": "OAUTH", "ownership": "LIBRARY", "clientId": "opaa",
             "clientSecret": "geheim"}
            """
                .formatted(name, PROBE_SERVER),
            profiles);
    library("PROFILE_OAUTH_PROBE", profile, PROBE_SERVER + "/a", null);
    library("PROFILE_OAUTH_PROBE", profile, PROBE_SERVER + "/b", null);

    Saved saved =
        previewThenSave(
            profile,
            """
            {"name": "%s", "serverUrl": "%s", "authMethod": "OAUTH", "ownership": "LIBRARY",
             "clientId": "anders"}
            """
                .formatted(name, PROBE_SERVER),
            true);

    assertThat(saved.connectionsDiscarded()).isEqualTo(2);
    assertThat(saved.secretsDiscarded()).isZero();
    assertThat(saved.configurationsChanged()).isZero();
    assertThat(saved.confirmation()).contains("2 Verbindungen");
  }

  /**
   * An ownership without persons releases every private library from the profile, not only the ones
   * its connector refuses - and the preview says so, masked by their owners.
   */
  @Test
  void anOwnershipWithoutPersonsReleasesEveryPrivateLibraryAsThePreviewSays() throws Exception {
    String name = "Zugang Private " + UUID.randomUUID();
    UUID profile = personProfile(name, "BOTH", null);
    personLibrary(profile, "/a", null);
    for (int index = 0; index < groupSize.minimumGroupSize(); index++) {
      privateLibrary(profile, owner());
    }

    previewThenSave(profile, personChange(name, "LIBRARY", null), true);

    assertThat(privateLibraries)
        .allSatisfy(library -> assertThat(onProfile(library, profile)).isFalse());
  }

  /**
   * Previews {@code change}, saves it - answering the 409 where the preview names a confirmation,
   * whose text must be the 409's - and checks that the preview named what saving did.
   */
  private Saved previewThenSave(UUID profile, String change, boolean asksConfirmation)
      throws Exception {
    Map<UUID, Boolean> heldBefore = heldSecrets();
    long accountsBefore = openAccounts(profile);
    List<UUID> privateBefore =
        privateLibraries.stream().filter(library -> onProfile(library, profile)).toList();
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
    List<UUID> released =
        privateBefore.stream().filter(library -> !onProfile(library, profile)).toList();
    long releasedOwners = released.stream().map(privateOwners::get).distinct().count();

    long previewedSecrets = ((Number) preview.get("secretsDiscarded")).longValue();
    long previewedConfigurations = ((Number) preview.get("configurationsChanged")).longValue();
    assertThat(previewedSecrets).as("secretsDiscarded").isEqualTo(secretsDiscarded);
    assertThat(previewedConfigurations).as("configurationsChanged").isEqualTo(changed.size());
    if (accountsBefore > 0) {
      assertThat(preview.get("connectedAccountsEnded") != null)
          .as("connectedAccountsEnded")
          .isEqualTo(accountsEnded);
    }
    assertMaskedLike(
        (Map<?, ?>) preview.get("rejectedPrivateLibraries"), released.size(), releasedOwners);
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

  /**
   * The masked count of released private libraries admits what saving released: exact only as the
   * number, "fewer than N" only below N owners, withheld only from N owners on.
   */
  private void assertMaskedLike(Map<?, ?> told, long libraries, long ownersOf) {
    int minimum = groupSize.minimumGroupSize();
    if (told == null) {
      assertThat(ownersOf == 0 || ownersOf >= minimum)
          .as("rejectedPrivateLibraries withheld, released %d of %d owners", libraries, ownersOf)
          .isTrue();
    } else if (told.get("count") != null) {
      assertThat(((Number) told.get("count")).longValue())
          .as("rejectedPrivateLibraries")
          .isEqualTo(libraries);
    } else {
      assertThat(ownersOf)
          .as("rejectedPrivateLibraries %s, released %d of %d owners", told, libraries, ownersOf)
          .isLessThan(((Number) told.get("fewerThan")).longValue());
    }
  }

  private boolean onProfile(UUID library, UUID profile) {
    return jdbc.queryForObject(
            "SELECT count(*) FROM library_connections WHERE library_id = ? AND profile_id = ?",
            Long.class,
            library,
            profile)
        > 0;
  }

  /** A person of the organization, for a private library of her own. */
  private UUID owner() {
    UUID person = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, organization_id,"
            + " last_login_at) VALUES (?, ?, 'opaa-dev', ?, 'Besitzerin', ?, now())",
        person,
        "besitzerin-" + person,
        "besitzerin-" + person + "@example.com",
        Organization.DEFAULT_ID);
    owners.add(person);
    return person;
  }

  /** A private library of {@code owner} on her connected account, as its creation leaves it. */
  private void privateLibrary(UUID profile, UUID owner) {
    accounts.connect(
        CurrentUser.of(owner, Organization.DEFAULT_ID, SystemRole.USER, "Besitzerin"),
        profile,
        "besitzerin",
        PersonProbeSourceConnector.ACCEPTED_PASSWORD);
    UUID id =
        transactions.execute(
            status -> {
              KnowledgeLibrary saved =
                  libraryRepository.save(
                      KnowledgeLibrary.ownerOnly(
                          Organization.DEFAULT_ID,
                          "Meine Ablage " + UUID.randomUUID(),
                          null,
                          owner,
                          PersonProbeSourceConnector.TYPE,
                          null,
                          PERSON_SERVER + "/privat",
                          null,
                          null,
                          false));
              shellService.registerCreated(
                  saved, owner, Map.of("name", saved.getName(), "sourceType", "PERSON_PROBE"));
              return saved.getId();
            });
    privateLibraries.add(id);
    privateOwners.put(id, owner);
    jdbc.update(
        "INSERT INTO library_connections (library_id, profile_id, created_at, updated_at,"
            + " version) VALUES (?, ?, now(), now(), 0)",
        id,
        profile);
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
