package io.opaa.library.web;

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
import io.opaa.diagnosticaccess.LibraryDiagnosticsLockService;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceBlock.Reason;
import io.opaa.indexing.source.SourceConnectionBlockedException;
import io.opaa.indexing.source.SourceConnectionResolver;
import io.opaa.indexing.source.profileprobe.PersonProbeIndexingExecutor;
import io.opaa.indexing.source.profileprobe.PersonProbeSourceConnector;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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

/**
 * A private library created and run through the API with the test-only {@code PERSON_PROBE}
 * connector: it runs on its owner's connected account and nobody else's, rests with a reason its
 * owner can act on, cannot be shared, handed on or released, and is moved only by its owner onto
 * another profile for persons where she has an account.
 */
@OpaaIntegrationTest
class PrivateLibraryIntegrationTest {

  private static final String LIBRARIES = "/api/v1/libraries";
  private static final String PROFILES = "/api/v1/admin/connection-profiles";
  private static final String ME = "/api/v1/me/connected-accounts";
  private static final String SERVER = "https://person.example.org";
  private static final String PASSWORD = PersonProbeSourceConnector.ACCEPTED_PASSWORD;
  private static final String OWNER_ONLY_ASSET = "OWNER_ONLY_ASSET";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private SourceConnectionResolver resolver;
  @Autowired private LibraryDiagnosticsLockService diagnosticsLocks;
  @Autowired private PersonProbeIndexingExecutor executor;
  @Autowired private io.opaa.connection.account.ConnectedAccountService accounts;
  @Autowired private io.opaa.library.PrivateLibraryCreation privateCreation;
  @Autowired private io.opaa.permission.GroupSizeProperties groupSize;

  private final List<UUID> libraries = new ArrayList<>();
  private final List<UUID> profiles = new ArrayList<>();
  private final List<UUID> extraUsers = new ArrayList<>();
  private UUID owner;
  private UUID admin;
  private UUID forPersons;

  @BeforeEach
  void aConnectedAccountOnAProfileForPersons() throws Exception {
    mockMvc.perform(as("dev-user", get("/api/v1/spaces"))).andExpect(status().isOk());
    mockMvc.perform(as("dev-admin", get("/api/v1/spaces"))).andExpect(status().isOk());
    owner = userIdOf("dev-user@opaa.local");
    admin = userIdOf("admin@opaa.local");
    forPersons = profile("PERSON", SERVER);
    connect("dev-user", forPersons).andExpect(status().isOk());
  }

  @AfterEach
  void removeOwnRows() {
    jdbc.update(
        "UPDATE users SET directory_locked_at = NULL, last_login_at = now() WHERE id = ?", owner);
    for (UUID library : libraries) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", library.toString());
      jdbc.update("DELETE FROM asset_grant_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", library);
    }
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    libraries.clear();
    for (UUID profile : profiles) {
      jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
      jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
      ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
    profiles.clear();
    for (UUID extra : extraUsers) {
      jdbc.update("DELETE FROM asset_ownership_history WHERE owner_user_id = ?", extra);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", extra.toString());
      jdbc.update("DELETE FROM users WHERE id = ?", extra);
    }
    extraUsers.clear();
  }

  @Test
  void theOwnerCreatesAPrivateLibraryOnHerConnectedAccountAndItRunsWithHerSecret()
      throws Exception {
    String body =
        create(forPersons, "\"sourceUrl\": \"" + SERVER + "/ablage\"")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.privateLibrary").value(true))
            .andExpect(jsonPath("$.myRole").value("OWNER"))
            .andExpect(jsonPath("$.connectionProfile.id").value(forPersons.toString()))
            .andExpect(jsonPath("$.sourceBlock").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID library = idOf(body);

    assertThat(
            jdbc.queryForObject(
                "SELECT owner_only FROM assets WHERE id = ?", Boolean.class, library))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT source_credentials FROM knowledge_libraries WHERE id = ?",
                String.class,
                library))
        .isNull();
    assertThat(resolver.currentSecret(libraryRepository.findById(library).orElseThrow()).value())
        .isEqualTo("avogt:" + PASSWORD);
    assertThat(auditNamesOf(library)).isNotEmpty().containsOnly("Private Bibliothek");
    mockMvc
        .perform(as("dev-user", get(LIBRARIES)))
        .andExpect(jsonPath("$[?(@.id == '" + library + "')].privateLibrary").value(true));

    run(library, "COMPLETED");
  }

  @Test
  void aPrivateLibraryIsRefusedWithoutItsPreconditions() throws Exception {
    UUID forLibraries = profile("LIBRARY", SERVER);
    UUID withoutAccount = profile("PERSON", SERVER);

    create(null, "").andExpect(status().isBadRequest());
    create(forLibraries, "").andExpect(status().isBadRequest());
    create(withoutAccount, "")
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error")
                .value(org.hamcrest.Matchers.containsString("kein verbundenes Konto")));
    create(forPersons, "\"ownerType\": \"GROUP\", \"ownerId\": \"" + UUID.randomUUID() + "\"")
        .andExpect(status().isBadRequest());
    create(forPersons, "\"sourceCredentials\": \"avogt:" + PASSWORD + "\"")
        .andExpect(status().isBadRequest());
    // the binding of the connected account is the profile's; a share of its own is another target
    create(forPersons, "\"sourceSettings\": {\"share\": \"andere\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Ziel")));
    create(forPersons, "\"sourceUrl\": \"https://andere.example.org/ablage\"")
        .andExpect(status().isBadRequest());

    ConnectorReleases.withdraw(jdbc, "PROFILE:" + forPersons);
    create(forPersons, "")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM assets WHERE owner_user_id = ? AND owner_only",
                Integer.class,
                owner))
        .isZero();
  }

  /** Acceptance criteria of #2164 over HTTP, with a library the API created. */
  @Test
  void nobodyCanShareHandOnOrReleaseAPrivateLibraryCreatedThroughTheApi() throws Exception {
    UUID library = createdPrivateLibrary();

    refusedByTheRule(grant(library, "dev-user", "USER", admin));
    refusedByTheRule(grant(library, "dev-user", "ALL_ACCOUNTS", null));
    refusedByTheRule(
        mockMvc.perform(
            as(
                    "dev-user",
                    post("/api/v1/assets/KNOWLEDGE_LIBRARY/" + library + "/transfer-ownership"))
                .content("{\"ownerType\":\"USER\",\"ownerId\":\"" + admin + "\"}")));
    refusedByTheRule(release(library, "dev-user"));

    unknown(grant(library, "dev-admin", "USER", admin));
    unknown(release(library, "dev-admin"));
    unknown(
        mockMvc.perform(
            as("dev-admin", put(LIBRARIES + "/" + library + "/share-cap"))
                .content("{\"allAccountsGrantAllowed\": true}")));
    unknown(
        mockMvc.perform(
            as("dev-admin", put(LIBRARIES + "/" + library + "/share-cap"))
                .content("{\"allAccountsGrantAllowed\": false}")));
    // a lowered cap changes nothing for the rule either
    refusedByTheRule(grant(library, "dev-user", "ALL_ACCOUNTS", null));

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM asset_grants WHERE asset_id = ?", Integer.class, library))
        .isEqualTo(1);
  }

  /** Only the owner moves a private library, and only onto a profile for persons she connected. */
  @Test
  void onlyItsOwnerMovesAPrivateLibraryAndOnlyOntoHerOwnConnectedAccount() throws Exception {
    UUID library = createdPrivateLibrary();
    UUID forLibraries = profile("LIBRARY", SERVER);
    UUID withoutAccount = profile("PERSON", SERVER);
    UUID second = profile("PERSON", SERVER);
    connect("dev-user", second).andExpect(status().isOk());

    unknown(move(library, "dev-admin", second));
    move(library, "dev-user", forLibraries).andExpect(status().isBadRequest());
    move(library, "dev-user", withoutAccount).andExpect(status().isBadRequest());
    mockMvc
        .perform(as("dev-user", delete(LIBRARIES + "/" + library + "/connection-profile")))
        .andExpect(status().isBadRequest());
    unknown(
        mockMvc.perform(
            as("dev-admin", delete(LIBRARIES + "/" + library + "/connection-profile"))));

    move(library, "dev-user", second)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connectionProfile.id").value(second.toString()));
    assertThat(resolver.currentSecret(libraryRepository.findById(library).orElseThrow()).value())
        .isEqualTo("avogt:" + PASSWORD);
  }

  /**
   * The profile options offer a profile for persons only to a person with an account of her own on
   * it, name its ownership and her account, and never offer one for a shared library.
   */
  @Test
  void aProfileForPersonsIsOfferedOnlyWhereTheCallerHasAnAccountOfHerOwn() throws Exception {
    UUID withoutAccount = profile("PERSON", SERVER);
    UUID forLibraries = profile("LIBRARY", SERVER);
    String options = "/api/v1/connection-profiles?sourceType=PERSON_PROBE";

    String mine = optionsOf("dev-user", options);
    assertThat(field(mine, forPersons, "ownership")).containsExactly("PERSON");
    assertThat(field(mine, forPersons, "ownAccount")).containsExactly(true);
    assertThat(field(mine, forPersons, "creatable")).containsExactly(true);
    assertThat(field(mine, withoutAccount, "id")).isEmpty();
    assertThat(field(mine, forLibraries, "ownership")).containsExactly("LIBRARY");
    assertThat(field(mine, forLibraries, "ownAccount")).containsExactly(false);
    assertThat(field(optionsOf("dev-admin", options), forPersons, "id")).isEmpty();

    UUID library = createdPrivateLibrary();
    assertThat(field(optionsOf("dev-user", options + "&libraryId=" + library), forPersons, "id"))
        .containsExactly(forPersons.toString());
    connect("dev-admin", forPersons).andExpect(status().isOk());
    UUID shared = sharedLibraryOn(forLibraries);
    assertThat(field(optionsOf("dev-admin", options + "&libraryId=" + shared), forPersons, "id"))
        .isEmpty();
    assertThat(field(optionsOf("dev-admin", options), forPersons, "ownAccount"))
        .containsExactly(true);
  }

  /**
   * A type reached only through a profile for persons can be chosen by a person with an account of
   * her own on it - for her private library - and by nobody else for that reason.
   */
  @Test
  void aProfileForPersonsWithAnOwnAccountMakesItsTypeCreatable() throws Exception {
    String withAccount = body(mockMvc.perform(as("dev-user", get("/api/v1/source-types"))));
    jdbc.update(
        "DELETE FROM connected_accounts WHERE profile_id = ? AND user_id = ?", forPersons, owner);
    String without = body(mockMvc.perform(as("dev-user", get("/api/v1/source-types"))));

    assertThat(
            JsonPath.<List<Object>>read(
                withAccount, "$[?(@.type == 'PERSON_PROBE')].creatableWithOwnAddress"))
        .containsExactly(false);
    assertThat(JsonPath.<List<Object>>read(withAccount, "$[?(@.type == 'PERSON_PROBE')].creatable"))
        .containsExactly(true);
    assertThat(JsonPath.<List<Object>>read(without, "$[?(@.type == 'PERSON_PROBE')].creatable"))
        .containsExactly(false);
  }

  /** The page "Verbundene Konten" names the source type of every account and profile. */
  @Test
  void connectedAccountsAndConnectableProfilesNameTheirSourceType() throws Exception {
    UUID withoutAccount = profile("PERSON", SERVER);

    mockMvc
        .perform(as("dev-user", get(ME)))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.accounts[?(@.profileId == '" + forPersons + "')].sourceType")
                .value("PERSON_PROBE"))
        .andExpect(
            jsonPath("$.connectable[?(@.profileId == '" + withoutAccount + "')].sourceType")
                .value("PERSON_PROBE"));
  }

  /** Without a usable connection the library rests with a reason its owner can act on. */
  @Test
  void aPrivateLibraryRestsWithTheReasonOfItsOwnersConnection() throws Exception {
    UUID library = createdPrivateLibrary();

    mockMvc
        .perform(as("dev-user", delete(ME + "/" + forPersons)))
        .andExpect(status().isNoContent());
    blockOf(library, "NOT_CONNECTED", "Besitzerin der Bibliothek", "„Verbundene Konten“");
    actionOf(library, "CONNECT_OWN_ACCOUNT");
    run(library, "FAILED");
    mockMvc
        .perform(as("dev-user", get(LIBRARIES + "/" + library + "/indexing/runs")))
        .andExpect(jsonPath("$.runs[0].failureCategory").value("NOT_CONNECTED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT failure_category FROM indexing_jobs WHERE library_id = ?"
                    + " ORDER BY started_at DESC LIMIT 1",
                String.class,
                library))
        .isEqualTo("NOT_CONNECTED");

    connect("dev-user", forPersons).andExpect(status().isOk());
    resolver.credentialsRejected(libraryRepository.findById(library).orElseThrow());
    blockOf(library, "EXPIRED", "Besitzerin der Bibliothek", "neu");
    actionOf(library, "CONNECT_OWN_ACCOUNT");

    // a resting or deactivated owner signs in no more, so the port answers in her place
    connect("dev-user", forPersons).andExpect(status().isOk());
    jdbc.update("UPDATE users SET last_login_at = now() - interval '200 days' WHERE id = ?", owner);
    assertThat(refusalOf(library).reason()).isEqualTo(Reason.DORMANT);
    assertThat(refusalOf(library).responsible())
        .isEqualTo("Besitzerin der Bibliothek bzw. Systemverwaltung");

    jdbc.update(
        "UPDATE users SET last_login_at = now(), directory_locked_at = now() WHERE id = ?", owner);
    assertThat(refusalOf(library).reason()).isEqualTo(Reason.OWNER_DEACTIVATED);
    assertThat(refusalOf(library).responsible()).isEqualTo("Systemverwaltung");
  }

  /** A target the account is not issued for has its own notice, not "no connected account". */
  @Test
  void aPrivateLibraryReachingAnotherTargetHasItsOwnNotice() throws Exception {
    UUID library = createdPrivateLibrary();

    mockMvc
        .perform(
            as("dev-user", put(LIBRARIES + "/" + library))
                .content(
                    "{\"name\": \"Meine Ablage\", \"sourceSettings\": {\"share\": \"andere\"}}"))
        .andExpect(status().isBadRequest());
    jdbc.update(
        "UPDATE knowledge_libraries SET source_settings = '{\"share\": \"andere\"}' WHERE id = ?",
        library);

    run(library, "FAILED");
    String runs =
        mockMvc
            .perform(as("dev-user", get(LIBRARIES + "/" + library + "/indexing/runs")))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(JsonPath.<String>read(runs, "$.runs[0].failureCategory"))
        .isEqualTo("TARGET_OUTSIDE_PROFILE");
    assertThat(JsonPath.<String>read(runs, "$.runs[0].message"))
        .contains("Ziel weicht ab")
        .doesNotContain("kein verbundenes Konto");
  }

  /** The probe and the listing before and after creating it run on a profile for persons alone. */
  @Test
  void theConnectionTestAndTheListingWorkOnAProfileForPersonsAlone() throws Exception {
    String draft =
        "\"sourceType\": \"PERSON_PROBE\", \"connectionProfileId\": \"" + forPersons + "\"";

    mockMvc
        .perform(
            as("dev-user", post(LIBRARIES + "/source-test"))
                .content("{" + draft + ", \"privateLibrary\": true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reachable").value(true));
    mockMvc
        .perform(
            as("dev-user", post("/api/v1/source-types/PERSON_PROBE/browse"))
                .content(
                    "{\"sourceUrl\": \""
                        + SERVER
                        + "\", \"connectionProfileId\": \""
                        + forPersons
                        + "\", \"privateLibrary\": true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries[0].key").value(PersonProbeSourceConnector.LISTED_FOLDER));
    mockMvc
        .perform(
            as("dev-user", post(LIBRARIES + "/source-test"))
                .content(
                    "{" + draft + ", \"privateLibrary\": true, \"sourceCredentials\": \"a:b\"}"))
        .andExpect(status().isBadRequest());
    // without privateLibrary the profile for persons admits no library of its own
    mockMvc
        .perform(as("dev-user", post(LIBRARIES + "/source-test")).content("{" + draft + "}"))
        .andExpect(status().isBadRequest());

    UUID library = createdPrivateLibrary();
    mockMvc
        .perform(
            as("dev-user", post(LIBRARIES + "/source-test"))
                .content("{\"sourceType\": \"PERSON_PROBE\", \"libraryId\": \"" + library + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reachable").value(true));
    mockMvc
        .perform(
            as("dev-user", post("/api/v1/source-types/PERSON_PROBE/browse"))
                .content("{\"sourceUrl\": \"" + SERVER + "\", \"libraryId\": \"" + library + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries[0].key").value(PersonProbeSourceConnector.LISTED_FOLDER));
  }

  /**
   * The decision on the Diagnosesperre: a private library carries it from its creation and keeps it
   * - "Sicht als" never reaches it anyway - and the number a diagnosis names does not count it.
   */
  @Test
  void aPrivateLibraryStaysDiagnosticsLockedAndIsNotCounted() throws Exception {
    long locked = diagnosticsLocks.countLocked(Organization.DEFAULT_ID);
    UUID library = createdPrivateLibrary();

    mockMvc
        .perform(as("dev-user", get(LIBRARIES + "/" + library)))
        .andExpect(jsonPath("$.diagnosticsLocked").value(true))
        .andExpect(jsonPath("$.diagnosticsLockToggleable").value(false));
    mockMvc
        .perform(
            as("dev-user", put(LIBRARIES + "/" + library + "/diagnostics-lock"))
                .content("{\"locked\": false}"))
        .andExpect(status().isBadRequest());
    unknown(
        mockMvc.perform(
            as("dev-admin", put(LIBRARIES + "/" + library + "/diagnostics-lock"))
                .content("{\"locked\": false}")));

    assertThat(diagnosticsLocks.countLocked(Organization.DEFAULT_ID)).isEqualTo(locked);
    assertThat(diagnosticsLocks.isLocked(library)).isTrue();
  }

  /**
   * A private library vetoes no change of its profile: its refusal is counted, never named, and the
   * library rests until its owner moves it; a profile without persons releases it too.
   */
  @Test
  void aPrivateLibraryVetoesNoProfileChangeAndTellsTheAdministrationNothing() throws Exception {
    UUID library = createdPrivateLibrary();
    String refusedAddress = "https://" + PersonProbeSourceConnector.REFUSED_HOST;
    String change =
        "{\"name\": \"%s\", \"serverUrl\": \"%s\", \"authMethod\": \"PERSONAL_SECRET\","
            + " \"ownership\": \"PERSON\", \"confirmDiscard\": true}";
    String name = profileName(forPersons);

    String preview =
        mockMvc
            .perform(
                as("dev-admin", post(PROFILES + "/" + forPersons + "/impact"))
                    .content(change.formatted(name, refusedAddress)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rejectedLibraries").value(0))
            .andExpect(jsonPath("$.rejectedPrivateLibraries.fewerThan").value(5))
            .andExpect(jsonPath("$.rejectedPrivateLibraries.count").doesNotExist())
            .andExpect(jsonPath("$.rejections").isEmpty())
            .andExpect(jsonPath("$.connections").value(0))
            .andExpect(jsonPath("$.libraries").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(preview)
        .doesNotContain(library.toString())
        .doesNotContain(PersonProbeSourceConnector.REFUSED_FOLDER);

    mockMvc
        .perform(
            as("dev-admin", put(PROFILES + "/" + forPersons))
                .content(change.formatted(name, refusedAddress)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connectionCount").value(0));

    assertThat(profileOf(library)).isNull();
    assertThat(releaseNotices(library)).isEqualTo(1);
    mockMvc
        .perform(as("dev-user", get(LIBRARIES + "/" + library)))
        .andExpect(jsonPath("$.sourceBlock.reason").value("ACCESS_REMOVED"))
        .andExpect(
            jsonPath("$.sourceBlock.notice")
                .value(org.hamcrest.Matchers.containsString("einem anderen Zugang zu")));
    assertThat(auditNamesOf(library)).containsOnly("Private Bibliothek");
  }

  /**
   * A secret the store refuses - here for a deactivated owner - is no secret to the change: the
   * preview and the change answer as for a library without one instead of failing.
   */
  @Test
  void aProfileChangeOverAPrivateLibraryOfADeactivatedOwnerIsAnswered() throws Exception {
    UUID library = createdPrivateLibrary();
    jdbc.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", owner);
    String change =
        "{\"name\": \"%s\", \"serverUrl\": \"https://anders.example.org\","
            + " \"authMethod\": \"PERSONAL_SECRET\", \"ownership\": \"PERSON\","
            + " \"confirmDiscard\": true}";
    String name = profileName(forPersons);

    mockMvc
        .perform(
            as("dev-admin", post(PROFILES + "/" + forPersons + "/impact"))
                .content(change.formatted(name)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rejectedLibraries").value(0))
        .andExpect(jsonPath("$.rejectedPrivateLibraries.fewerThan").value(5));
    mockMvc
        .perform(as("dev-admin", put(PROFILES + "/" + forPersons)).content(change.formatted(name)))
        .andExpect(status().isOk());
    assertThat(libraryRepository.findById(library)).isPresent();
  }

  /**
   * A secret the store refuses is no refusal of the connector: the private library of a deactivated
   * owner is not asked, so it stays on its profile instead of being released for a check it could
   * not pass without her secret.
   */
  @Test
  void aRefusedSecretLeavesThePrivateLibraryOnItsProfile() throws Exception {
    UUID library = createdPrivateLibrary();
    jdbc.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", owner);
    String change =
        "{\"name\": \"%s\", \"serverUrl\": \"https://anders.example.org\","
            + " \"authMethod\": \"PERSONAL_SECRET\", \"ownership\": \"PERSON\","
            + " \"confirmDiscard\": true}";

    mockMvc
        .perform(
            as("dev-admin", put(PROFILES + "/" + forPersons))
                .content(change.formatted(profileName(forPersons))))
        .andExpect(status().isOk());

    assertThat(profileOf(library)).isEqualTo(forPersons);
    assertThat(releaseNotices(library)).isZero();
  }

  /**
   * The refused private libraries of a profile are a part of the organization's: told exactly only
   * where both their owners and the owners of every other private library reach the minimum group
   * size. Five owners on the profile, one of them with a second library elsewhere: the number would
   * be set off against the index status and point at that one person, so it is not told.
   */
  @Test
  void refusedPrivateLibrariesAreNotToldWhereTheRestRestsOnFewOwners() throws Exception {
    UUID elsewhere = profile("PERSON", SERVER);
    List<io.opaa.auth.CurrentUser> persons = new ArrayList<>();
    for (int index = 0; index < groupSize.minimumGroupSize(); index++) {
      UUID person = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO users (id, subject, issuer, email, display_name, organization_id,"
              + " last_login_at) VALUES (?, ?, 'opaa-dev', ?, 'Besitzerin', ?, now())",
          person,
          "besitzerin-" + person,
          "besitzerin-" + person + "@example.com",
          Organization.DEFAULT_ID);
      extraUsers.add(person);
      io.opaa.auth.CurrentUser caller =
          io.opaa.auth.CurrentUser.of(
              person, Organization.DEFAULT_ID, io.opaa.api.types.SystemRole.USER, "Besitzerin");
      accounts.connect(caller, forPersons, "person" + index, PASSWORD);
      libraries.add(privateCreation.create(privateOn(forPersons), caller));
      persons.add(caller);
    }
    accounts.connect(persons.getFirst(), elsewhere, "person0", PASSWORD);
    libraries.add(privateCreation.create(privateOn(elsewhere), persons.getFirst()));
    String change =
        "{\"name\": \"%s\", \"serverUrl\": \"https://%s\", \"authMethod\": \"PERSONAL_SECRET\","
            + " \"ownership\": \"PERSON\"}";

    mockMvc
        .perform(
            as("dev-admin", post(PROFILES + "/" + forPersons + "/impact"))
                .content(
                    change.formatted(
                        profileName(forPersons), PersonProbeSourceConnector.REFUSED_HOST)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rejectedPrivateLibraries").doesNotExist());
  }

  private io.opaa.library.LibraryCreation privateOn(UUID profile) {
    return new io.opaa.library.LibraryCreation(
        "Ablage " + UUID.randomUUID(),
        null,
        null,
        null,
        PersonProbeSourceConnector.TYPE,
        null,
        java.net.URI.create(SERVER + "/ablage"),
        null,
        null,
        null,
        null,
        null,
        profile);
  }

  @Test
  void aProfileThatStopsAdmittingPersonsReleasesItsPrivateLibraries() throws Exception {
    UUID both = profile("BOTH", SERVER);
    connect("dev-user", both).andExpect(status().isOk());
    UUID library = idOf(body(create(both, "").andExpect(status().isCreated())));
    String change =
        "{\"name\": \"%s\", \"serverUrl\": \"%s\", \"authMethod\": \"PERSONAL_SECRET\","
            + " \"ownership\": \"LIBRARY\", \"confirmDiscard\": true}";

    mockMvc
        .perform(
            as("dev-admin", put(PROFILES + "/" + both))
                .content(change.formatted(profileName(both), SERVER)))
        .andExpect(status().isOk());

    assertThat(profileOf(library)).isNull();
    mockMvc
        .perform(as("dev-user", get(LIBRARIES + "/" + library)))
        .andExpect(jsonPath("$.sourceBlock.reason").value("ACCESS_REMOVED"))
        .andExpect(
            jsonPath("$.sourceBlock.responsible")
                .value("Besitzerin der Bibliothek bzw. Systemverwaltung"));
  }

  /**
   * A changed default only the profile sets counts and waits for the shared libraries alone: a
   * private one is told to the administration in no number and no refusal, even while it runs. Its
   * run state is discarded like theirs and stays discarded once its run ends; its owner is told.
   */
  @Test
  void aRunningPrivateLibraryNeitherCountsInNorHoldsUpAFullSyncOfItsProfile() throws Exception {
    UUID both = profile("BOTH", SERVER, "eins");
    connect("dev-user", both).andExpect(status().isOk());
    UUID library = idOf(body(create(both, "").andExpect(status().isCreated())));
    UUID shared = sharedLibraryOn(both);
    PersonProbeIndexingExecutor.Hold hold = executor.holdNextRun(library);
    // the test context runs a triggered run on the caller's thread
    java.util.concurrent.CompletableFuture<Void> running =
        java.util.concurrent.CompletableFuture.runAsync(
            () -> {
              try {
                mockMvc
                    .perform(as("dev-user", post(LIBRARIES + "/" + library + "/indexing")))
                    .andExpect(status().isAccepted());
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    hold.awaitEntered();
    assertThat(syncStates(library)).isEqualTo(1);
    String change =
        "{\"name\": \"%s\", \"serverUrl\": \"%s\", \"authMethod\": \"PERSONAL_SECRET\","
                .formatted(profileName(both), SERVER)
            + " \"ownership\": \"BOTH\", \"connectorSettings\": {\"realm\": \"zwei\"}%s}";

    try {
      mockMvc
          .perform(
              as("dev-admin", post(PROFILES + "/" + both + "/impact"))
                  .content(change.formatted("")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.fullSyncLibraries").value(1));
      mockMvc
          .perform(as("dev-admin", put(PROFILES + "/" + both)).content(change.formatted("")))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("CONNECTION_PROFILE_CONFIRMATION_REQUIRED"))
          .andExpect(
              jsonPath("$.error")
                  .value(org.hamcrest.Matchers.containsString("von 1 Bibliothek wird")));
      mockMvc
          .perform(
              as("dev-admin", put(PROFILES + "/" + both))
                  .content(change.formatted(", \"confirmDiscard\": true")))
          .andExpect(status().isOk());
    } finally {
      hold.release();
    }

    running.get(30, java.util.concurrent.TimeUnit.SECONDS);
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                mockMvc
                    .perform(as("dev-user", get(LIBRARIES + "/" + library + "/indexing/status")))
                    .andExpect(jsonPath("$.status").value("COMPLETED")));
    assertThat(syncStates(library)).as("the run state the change discarded").isZero();
    assertThat(profileOf(library)).isEqualTo(both);
    assertThat(fullSyncNotices(owner, library)).isEqualTo(1);
    assertThat(fullSyncNotices(admin, shared)).isEqualTo(1);
  }

  // -------------------------------------------------------------------------------------------

  /** A shared library of the administration on {@code profile}, with its own secret. */
  private UUID sharedLibraryOn(UUID profile) throws Exception {
    ResultActions result =
        mockMvc.perform(
            as("dev-admin", post(LIBRARIES))
                .content(
                    """
                    {"name": "Geteilte Ablage", "sourceType": "PERSON_PROBE",
                     "connectionProfileId": "%s", "sourceUrl": "%s/geteilt",
                     "sourceCredentials": "geteilt:%s"}
                    """
                        .formatted(profile, SERVER, PASSWORD)));
    UUID id = idOf(body(result.andExpect(status().isCreated())));
    libraries.add(id);
    return id;
  }

  private int syncStates(UUID library) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM source_sync_state WHERE library_id = ?", Integer.class, library);
  }

  private int fullSyncNotices(UUID recipient, UUID library) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM notifications WHERE type = 'SOURCE_FULL_SYNC_FORCED'"
            + " AND recipient_user_id = ? AND object_id = ?",
        Integer.class,
        recipient,
        library);
  }

  private UUID createdPrivateLibrary() throws Exception {
    return idOf(
        body(
            create(forPersons, "\"sourceUrl\": \"" + SERVER + "/ablage\"")
                .andExpect(status().isCreated())));
  }

  private ResultActions create(UUID profile, String extra) throws Exception {
    String fields =
        "\"name\": \"Meine Ablage\", \"sourceType\": \"PERSON_PROBE\", \"privateLibrary\": true"
            + (profile == null ? "" : ", \"connectionProfileId\": \"" + profile + "\"")
            + (extra.isEmpty() ? "" : ", " + extra);
    ResultActions result =
        mockMvc.perform(as("dev-user", post(LIBRARIES)).content("{" + fields + "}"));
    String response = result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    if (result.andReturn().getResponse().getStatus() == 201) {
      libraries.add(idOf(response));
    }
    return result;
  }

  private ResultActions move(UUID library, String user, UUID profile) throws Exception {
    return mockMvc.perform(
        as(user, put(LIBRARIES + "/" + library + "/connection-profile"))
            .content("{\"profileId\": \"" + profile + "\"}"));
  }

  private ResultActions grant(UUID library, String user, String subjectType, UUID subjectId)
      throws Exception {
    String subject = subjectId == null ? "" : ",\"subjectId\":\"" + subjectId + "\"";
    return mockMvc.perform(
        as(user, post("/api/v1/assets/KNOWLEDGE_LIBRARY/" + library + "/grants"))
            .content(
                "{\"subjectType\":\"" + subjectType + "\",\"role\":\"VIEWER\"" + subject + "}"));
  }

  private ResultActions release(UUID library, String user) throws Exception {
    Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
    return mockMvc.perform(
        as(user, put(LIBRARIES + "/" + library + "/external-access"))
            .content("{\"enabled\":true,\"expiresAt\":\"" + expiresAt + "\"}"));
  }

  private void blockOf(UUID library, String reason, String responsible, String noticePart)
      throws Exception {
    mockMvc
        .perform(as("dev-user", get(LIBRARIES + "/" + library)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceBlock.reason").value(reason))
        .andExpect(jsonPath("$.sourceBlock.responsible").value(responsible))
        .andExpect(
            jsonPath("$.sourceBlock.notice")
                .value(org.hamcrest.Matchers.containsString(noticePart)));
  }

  private void actionOf(UUID library, String action) throws Exception {
    mockMvc
        .perform(as("dev-user", get(LIBRARIES + "/" + library)))
        .andExpect(jsonPath("$.sourceBlock.action").value(action));
  }

  private String optionsOf(String user, String uri) throws Exception {
    return body(mockMvc.perform(as(user, get(uri))).andExpect(status().isOk()));
  }

  private static List<Object> field(String options, UUID profile, String name) {
    return JsonPath.read(options, "$[?(@.id == '" + profile + "')]." + name);
  }

  private SourceBlock refusalOf(UUID library) {
    try {
      resolver.currentSecret(libraryRepository.findById(library).orElseThrow());
    } catch (SourceConnectionBlockedException e) {
      return e.block();
    }
    throw new AssertionError("the library is not blocked");
  }

  private void run(UUID library, String expectedStatus) throws Exception {
    mockMvc
        .perform(as("dev-user", post(LIBRARIES + "/" + library + "/indexing")))
        .andExpect(status().isAccepted());
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                mockMvc
                    .perform(as("dev-user", get(LIBRARIES + "/" + library + "/indexing/status")))
                    .andExpect(jsonPath("$.status").value(expectedStatus)));
  }

  private UUID profile(String ownership, String serverUrl) throws Exception {
    return profile(ownership, serverUrl, null);
  }

  /** A profile of the probe, with {@code realm} as its default only it sets or none. */
  private UUID profile(String ownership, String serverUrl, String realm) throws Exception {
    String name = "Zugang " + ownership + " " + UUID.randomUUID();
    String defaults =
        realm == null ? "" : ", \"connectorSettings\": {\"realm\": \"" + realm + "\"}";
    String body =
        body(
            mockMvc
                .perform(
                    as("dev-admin", post(PROFILES))
                        .content(
                            """
                            {"name": "%s", "sourceType": "PERSON_PROBE", "serverUrl": "%s",
                             "authMethod": "PERSONAL_SECRET", "ownership": "%s"%s}
                            """
                                .formatted(name, serverUrl, ownership, defaults)))
                .andExpect(status().isCreated()));
    UUID id = idOf(body);
    profiles.add(id);
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + id);
    return id;
  }

  private String profileName(UUID profile) {
    return jdbc.queryForObject(
        "SELECT name FROM connection_profiles WHERE id = ?", String.class, profile);
  }

  private ResultActions connect(String user, UUID profile) throws Exception {
    return mockMvc.perform(
        as(user, put(ME + "/" + profile))
            .content("{\"username\": \"avogt\", \"secret\": \"" + PASSWORD + "\"}"));
  }

  private int releaseNotices(UUID library) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM notifications WHERE type = 'PRIVATE_LIBRARY_RELEASED'"
            + " AND recipient_user_id = ? AND object_id = ?",
        Integer.class,
        owner,
        library);
  }

  private UUID profileOf(UUID library) {
    return jdbc.queryForObject(
        "SELECT profile_id FROM library_connections WHERE library_id = ?", UUID.class, library);
  }

  private List<String> auditNamesOf(UUID library) {
    return jdbc.queryForList(
        "SELECT object_label FROM audit_log WHERE object_id = ?", String.class, library.toString());
  }

  private static void refusedByTheRule(ResultActions result) throws Exception {
    result.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(OWNER_ONLY_ASSET));
  }

  private static void unknown(ResultActions result) throws Exception {
    result.andExpect(status().isNotFound());
  }

  private static String body(ResultActions result) throws Exception {
    return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private static UUID idOf(String body) {
    return UUID.fromString(JsonPath.read(body, "$.id"));
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
