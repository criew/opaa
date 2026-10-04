package io.opaa.connection.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.asset.AssetShellService;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.UserRepository;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.SecretOwner.PersonOwned;
import io.opaa.connection.token.SecretRefusedException;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceBlock.Reason;
import io.opaa.indexing.source.SourceConnectionBlockedException;
import io.opaa.indexing.source.SourceConnectionResolver;
import io.opaa.indexing.source.profileprobe.PersonProbeIndexingExecutor;
import io.opaa.indexing.source.profileprobe.PersonProbeSourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A person's connected account through the API with the test-only {@code PERSON_PROBE} connector:
 * connecting with a sign-in first, the release only for a new account, the secret handed out only
 * to the target and while the account is usable, a private library reached with its owner's secret,
 * and every end of a connection logged as the person's.
 */
@OpaaIntegrationTest
class ConnectedAccountIntegrationTest {

  private static final String ME = "/api/v1/me/connected-accounts";
  private static final String ADMIN = "/api/v1/admin/connection-profiles";
  private static final String SERVER = "https://person.example.org";
  private static final String PASSWORD = PersonProbeSourceConnector.ACCEPTED_PASSWORD;
  private static final String CREDENTIALS = "{\"username\": \"avogt\", \"secret\": \"%s\"}";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ConnectionSecrets secrets;
  @Autowired private SourceConnectionResolver resolver;
  @Autowired private UserRepository users;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetShellService shellService;
  @Autowired private TransactionTemplate transactions;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private PersonProbeIndexingExecutor probe;

  private final List<UUID> libraries = new ArrayList<>();
  private UUID profile;
  private String profileName;
  private UUID person;
  private UUID admin;

  @BeforeEach
  void aReleasedProfileForPersons() throws Exception {
    mockMvc.perform(as("dev-user", get("/api/v1/spaces"))).andExpect(status().isOk());
    mockMvc.perform(as("dev-admin", get("/api/v1/spaces"))).andExpect(status().isOk());
    person = userIdOf("dev-user@opaa.local");
    admin = userIdOf("admin@opaa.local");
    profileName = "Zugang Personen " + UUID.randomUUID();
    String body =
        mockMvc
            .perform(
                as("dev-admin", post(ADMIN))
                    .content(
                        """
                        {"name": "%s", "sourceType": "PERSON_PROBE",
                         "serverUrl": "%s", "authMethod": "PERSONAL_SECRET", "ownership": "PERSON"}
                        """
                            .formatted(profileName, SERVER)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    profile = UUID.fromString(JsonPath.read(body, "$.id"));
    ConnectorReleases.releaseToAllAccounts(jdbc, scope());
  }

  @AfterEach
  void removeOwnRows() {
    restoreAccount();
    jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
    jdbc.update(
        "DELETE FROM notifications WHERE type = 'CONNECTION_EXPIRED' AND recipient_user_id = ?",
        person);
    for (UUID library : libraries) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", library.toString());
      jdbc.update("DELETE FROM asset_grant_history WHERE asset_id = ?", library);
    }
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    ConnectorReleases.withdraw(jdbc, scope());
    jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
    jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
  }

  @Test
  void aPersonConnectsReconnectsAndDisconnectsTheirOwnAccount() throws Exception {
    mockMvc
        .perform(as("dev-user", get(ME)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accounts[?(@.profileId == '" + profile + "')]").isEmpty())
        .andExpect(
            jsonPath("$.connectable[?(@.profileId == '" + profile + "')].secretForm")
                .value("USERNAME_AND_PASSWORD"))
        .andExpect(jsonPath("$.missingAccess.responsible").value("Systemverwaltung"));

    connect("dev-user", "falsch").andExpect(status().isBadRequest());
    assertThat(accountRows(person)).isZero();

    connect("dev-user", PASSWORD)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("CONNECTED"))
        .andExpect(jsonPath("$.accountLabel").value("avogt"))
        .andExpect(jsonPath("$.released").value(true));
    assertThat(
            jdbc.queryForObject(
                "SELECT t.secret_ciphertext FROM connection_tokens t JOIN connected_accounts a"
                    + " ON a.id = t.connected_account_id WHERE a.profile_id = ?",
                String.class,
                profile))
        .startsWith("enc:v1:")
        .doesNotContain(PASSWORD);
    assertThat(
            jdbc.queryForObject(
                "SELECT account_label_ciphertext FROM connected_accounts WHERE profile_id = ?",
                String.class,
                profile))
        .startsWith("enc:v1:")
        .doesNotContain("avogt");

    connect("dev-user", PASSWORD).andExpect(status().isOk());
    mockMvc.perform(as("dev-user", delete(ME + "/" + profile))).andExpect(status().isNoContent());
    mockMvc.perform(as("dev-user", delete(ME + "/" + profile))).andExpect(status().isNotFound());

    assertThat(accountRows(person)).isZero();
    assertThat(tokenRows()).isZero();
    assertThat(logEntries())
        .extracting(entry -> entry.get("event_type"), entry -> entry.get("cause"))
        .containsExactly(
            tuple("CONNECTED", null), tuple("RECONNECTED", null), tuple("DISCONNECTED", "SELF"));
  }

  /** Acceptance criterion of #2163: the release counts for a new account only. */
  @Test
  void withoutTheReleaseTheConnectionStaysVisibleDisconnectableAndReconnectableButNoNewOne()
      throws Exception {
    connect("dev-user", PASSWORD).andExpect(status().isOk());
    ConnectorReleases.withdraw(jdbc, scope());

    mockMvc
        .perform(as("dev-user", get(ME)))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.accounts[?(@.profileId == '" + profile + "')].released").value(false))
        .andExpect(
            jsonPath("$.accounts[?(@.profileId == '" + profile + "')].responsible")
                .value("Systemverwaltung"))
        .andExpect(jsonPath("$.connectable[?(@.profileId == '" + profile + "')]").isEmpty());
    connect("dev-user", PASSWORD)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("CONNECTED"));
    mockMvc.perform(as("dev-user", delete(ME + "/" + profile))).andExpect(status().isNoContent());

    connect("dev-user", PASSWORD)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));
    assertThat(accountRows(person)).isZero();
  }

  @Test
  void aLockedProfileRefusesAReconnectionButNeverTheDisconnection() throws Exception {
    connect("dev-user", PASSWORD).andExpect(status().isOk());
    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + profile + "/lock")).content("{\"locked\": true}"))
        .andExpect(status().isOk());

    connect("dev-user", PASSWORD)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CONNECTOR_LOCKED"));
    mockMvc.perform(as("dev-user", delete(ME + "/" + profile))).andExpect(status().isNoContent());
  }

  /** Acceptance criterion of #2163: a test connector gets the owner's secret through the core. */
  @Test
  void theTestConnectorReadsTheOwnersSecretThroughTheCore() throws Exception {
    connect("dev-user", PASSWORD).andExpect(status().isOk());
    UUID library = privateLibraryOnTheProfile();

    run(library, "COMPLETED");

    assertThat(probe.secretSeenBy(library)).contains("avogt:" + PASSWORD);
    mockMvc
        .perform(as("dev-user", get(ME)))
        .andExpect(
            jsonPath("$.accounts[?(@.profileId == '" + profile + "')].usedBy[*].id")
                .value(library.toString()));
  }

  /** Decision 2 of the plan: a disconnected account stays while a private library is on it. */
  @Test
  void aDisconnectedAccountStaysWhileAPrivateLibraryRunsOnItAndConnectsAgain() throws Exception {
    connect("dev-user", PASSWORD).andExpect(status().isOk());
    UUID library = privateLibraryOnTheProfile();

    mockMvc.perform(as("dev-user", delete(ME + "/" + profile))).andExpect(status().isNoContent());

    assertThat(stateOf(person)).isEqualTo("DISCONNECTED");
    assertThat(tokenRows()).isZero();
    assertThat(blockOf(library).reason()).isEqualTo(Reason.NOT_CONNECTED);
    assertThat(blockOf(library).responsible()).isEqualTo("Besitzerin der Bibliothek");
    assertThat(blockOf(library).notice()).contains("„Verbundene Konten“");

    connect("dev-user", PASSWORD).andExpect(status().isOk());
    assertThat(stateOf(person)).isEqualTo("CONNECTED");
    assertThat(resolver.currentSecret(libraryRepository.findById(library).orElseThrow()).value())
        .isEqualTo("avogt:" + PASSWORD);
  }

  @Test
  void aPersonsSecretIsHandedOutOnlyToItsTargetAndWhileTheirAccountIsUsable() throws Exception {
    connect("dev-user", PASSWORD).andExpect(status().isOk());
    PersonOwned owner = new PersonOwned(profile, person);

    assertThat(secrets.current(owner, SERVER).value()).isEqualTo("avogt:" + PASSWORD);
    assertRefused(() -> secrets.current(owner, "https://andere.example.org"), Reason.NOT_CONNECTED);

    jdbc.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", person);
    assertRefused(() -> secrets.current(owner, SERVER), Reason.OWNER_DEACTIVATED);
    assertThat(secrets.stateOf(owner)).contains(Reason.OWNER_DEACTIVATED);
    jdbc.update("UPDATE users SET directory_locked_at = NULL WHERE id = ?", person);

    jdbc.update(
        "UPDATE users SET last_login_at = now() - interval '200 days' WHERE id = ?", person);
    assertRefused(() -> secrets.current(owner, SERVER), Reason.DORMANT);
    assertThat(secrets.stateOf(owner)).contains(Reason.DORMANT);

    // the next sign-in lifts it without connecting anew
    jdbc.update("UPDATE users SET last_login_at = now() WHERE id = ?", person);
    assertThat(secrets.current(owner, SERVER).value()).isEqualTo("avogt:" + PASSWORD);
    assertThat(secrets.stateOf(owner)).isEmpty();
  }

  @Test
  void theEmergencyShutdownEndsEveryConnectionWithOneEntryEach() throws Exception {
    connect("dev-user", PASSWORD).andExpect(status().isOk());
    connect("dev-admin", PASSWORD).andExpect(status().isOk());

    mockMvc
        .perform(as("dev-admin", post(ADMIN + "/" + profile + "/disconnect-all")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connectedAccounts.count").doesNotExist())
        .andExpect(jsonPath("$.connectedAccounts.fewerThan").value(5));

    assertThat(accountRows(person) + accountRows(admin)).isZero();
    assertThat(tokenRows()).isZero();
    assertThat(logEntries())
        .filteredOn(entry -> "EMERGENCY_DISCONNECTED".equals(entry.get("event_type")))
        .hasSize(2)
        .allSatisfy(
            entry -> {
              assertThat(entry.get("owner_kind")).isEqualTo("PERSON");
              assertThat(entry.get("cause")).isEqualTo("EMERGENCY");
            });
  }

  @Test
  void aRejectedSecretExpiresTheConnectionAndTellsItsOwner() throws Exception {
    connect("dev-user", PASSWORD).andExpect(status().isOk());
    UUID library = privateLibraryOnTheProfile();
    KnowledgeLibrary loaded = libraryRepository.findById(library).orElseThrow();

    resolver.credentialsRejected(loaded);

    assertThat(stateOf(person)).isEqualTo("EXPIRED");
    assertThat(secrets.stateOf(new PersonOwned(profile, person))).contains(Reason.EXPIRED);
    assertThat(blockOf(library).reason()).isEqualTo(Reason.EXPIRED);
    // a second rejection of the same run's secret tells the owner nothing new
    resolver.credentialsRejected(loaded);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type = 'CONNECTION_EXPIRED'"
                    + " AND recipient_user_id = ? AND object_id = ?",
                Integer.class,
                person,
                library))
        .isEqualTo(1);
    assertThat(logEntries())
        .extracting(
            entry -> entry.get("event_type"),
            entry -> entry.get("cause"),
            entry -> entry.get("actor_ref"))
        .contains(tuple("EXPIRED", "PROVIDER_REJECTED", "SYSTEM"));

    connect("dev-user", PASSWORD).andExpect(status().isOk());
    assertThat(resolver.currentSecret(loaded).value()).isEqualTo("avogt:" + PASSWORD);
  }

  /**
   * Acceptance criterion of #2163: the connection of a private library is logged as the person's by
   * every writer, never as the library's.
   */
  @Test
  void aPrivateLibrarysConnectionIsLoggedAsThePersonsByEveryWriter() throws Exception {
    connect("dev-user", PASSWORD).andExpect(status().isOk());
    UUID library = privateLibraryOnTheProfile();
    resolver.credentialsRejected(libraryRepository.findById(library).orElseThrow());
    connect("dev-user", PASSWORD).andExpect(status().isOk());
    mockMvc.perform(as("dev-user", delete(ME + "/" + profile))).andExpect(status().isNoContent());
    connect("dev-user", PASSWORD).andExpect(status().isOk());
    mockMvc
        .perform(as("dev-admin", post(ADMIN + "/" + profile + "/disconnect-all")))
        .andExpect(status().isOk());
    connect("dev-user", PASSWORD).andExpect(status().isOk());
    mockMvc
        .perform(as("dev-admin", delete(ADMIN + "/" + profile)))
        .andExpect(status().isNoContent());

    List<Map<String, Object>> entries = logEntries();
    assertThat(entries)
        .extracting(entry -> entry.get("event_type"))
        .containsExactlyInAnyOrder(
            "CONNECTED",
            "EXPIRED",
            "RECONNECTED",
            "DISCONNECTED",
            "RECONNECTED",
            "EMERGENCY_DISCONNECTED",
            "RECONNECTED",
            "DELETED");
    assertThat(entries)
        .allSatisfy(
            entry -> {
              assertThat(entry.get("owner_kind")).isEqualTo("PERSON");
              assertThat(entry.get("person_ref")).isNotNull();
              assertThat(entry.get("library_id")).isNull();
              assertThat(entry.get("account_label")).isNull();
            });
  }

  /** A profile whose ownership no longer admits persons ends their connections. */
  @Test
  void aProfileThatNoLongerAdmitsPersonsEndsTheirConnectionsAndHandsOutNothing() throws Exception {
    connect("dev-user", PASSWORD).andExpect(status().isOk());
    UUID library = privateLibraryOnTheProfile();
    String change =
        """
        {"name": "%s", "serverUrl": "%s", "authMethod": "PERSONAL_SECRET",
         "ownership": "LIBRARY"%s}
        """;

    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + profile))
                .content(change.formatted(profileName, SERVER, "")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONNECTION_PROFILE_CONFIRMATION_REQUIRED"));
    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + profile))
                .content(change.formatted(profileName, SERVER, ", \"confirmDiscard\": true")))
        .andExpect(status().isOk());

    assertThat(tokenRows()).isZero();
    assertThat(stateOf(person)).isEqualTo("DISCONNECTED");
    assertThat(logEntries())
        .extracting(entry -> entry.get("event_type"), entry -> entry.get("cause"))
        .contains(tuple("DISCONNECTED", "PROFILE_CHANGED"));
    // even a secret that reached the store is not handed out on a profile without persons
    secrets.store(
        new PersonOwned(profile, person),
        io.opaa.connection.token.NewSecret.personal("avogt:" + PASSWORD),
        SERVER);
    assertThat(blockOf(library).reason()).isEqualTo(Reason.NOT_CONNECTED);
    connect("dev-user", PASSWORD).andExpect(status().isBadRequest());
  }

  @Test
  void anAccountWithAConnectionIsNotDeleted() throws Exception {
    connect("dev-user", PASSWORD).andExpect(status().isOk());

    assertThat(users.countDeletionBlockers(person).getConnectedAccounts()).isEqualTo(1);
  }

  @Test
  void theAdministrationSeesNumbersOnlyAndThoseMaskedBelowTheGroupSize() throws Exception {
    connect("dev-user", PASSWORD).andExpect(status().isOk());

    mockMvc
        .perform(as("dev-admin", get(ADMIN + "/" + profile)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connectedAccountCount.fewerThan").value(5))
        .andExpect(jsonPath("$.connectedAccountCount.count").doesNotExist())
        // one connected person: the expired part would tell how many are connected
        .andExpect(jsonPath("$.expiredConnectionCount.fewerThan").value(5));
    mockMvc
        .perform(as("dev-admin", get(ADMIN + "/" + profile + "/impact")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connectedAccounts.fewerThan").value(5));
  }

  private ResultActions connect(String user, String password) throws Exception {
    return mockMvc.perform(
        as(user, put(ME + "/" + profile)).content(CREDENTIALS.formatted(password)));
  }

  /** A private library of the person, connected through the profile as its creation will do. */
  private UUID privateLibraryOnTheProfile() {
    UUID id =
        transactions.execute(
            status -> {
              KnowledgeLibrary saved =
                  libraryRepository.save(
                      KnowledgeLibrary.ownerOnly(
                          Organization.DEFAULT_ID,
                          "Meine Ablage " + UUID.randomUUID(),
                          null,
                          person,
                          PersonProbeSourceConnector.TYPE,
                          null,
                          SERVER + "/ablage",
                          null,
                          null,
                          false));
              shellService.registerCreated(
                  saved, person, Map.of("name", saved.getName(), "sourceType", "PERSON_PROBE"));
              return saved.getId();
            });
    libraries.add(id);
    jdbc.update(
        "INSERT INTO library_connections (library_id, profile_id, created_at, updated_at,"
            + " version) VALUES (?, ?, now(), now(), 0)",
        id,
        profile);
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

  private SourceBlock blockOf(UUID library) {
    KnowledgeLibrary loaded = libraryRepository.findById(library).orElseThrow();
    try {
      resolver.currentSecret(loaded);
    } catch (SourceConnectionBlockedException e) {
      return e.block();
    }
    throw new AssertionError("the library is not blocked");
  }

  private static void assertRefused(Runnable call, Reason reason) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            SecretRefusedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
  }

  private String stateOf(UUID user) {
    return jdbc.queryForObject(
        "SELECT state FROM connected_accounts WHERE user_id = ? AND profile_id = ?",
        String.class,
        user,
        profile);
  }

  private int accountRows(UUID user) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM connected_accounts WHERE user_id = ? AND profile_id = ?",
        Integer.class,
        user,
        profile);
  }

  private int tokenRows() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM connection_tokens WHERE profile_id = ?", Integer.class, profile);
  }

  private List<Map<String, Object>> logEntries() {
    return jdbc.queryForList(
        "SELECT event_type, cause, owner_kind, person_ref, library_id, account_label, actor_ref"
            + " FROM connection_log WHERE profile_id = ? ORDER BY recorded_at",
        profile);
  }

  private void restoreAccount() {
    jdbc.update(
        "UPDATE users SET directory_locked_at = NULL, last_login_at = now() WHERE id = ?", person);
  }

  private String scope() {
    return "PROFILE:" + profile;
  }

  private UUID userIdOf(String email) {
    return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
