package io.opaa.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.ConnectionAuthorizationPurpose;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetShellService;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.common.PublicBaseUrl;
import io.opaa.common.PublicBaseUrlProperties;
import io.opaa.common.ValidationException;
import io.opaa.connection.account.ConnectedAccountService;
import io.opaa.connection.account.ConnectionExpiryWatch;
import io.opaa.connection.account.ConnectionLifecycleReconciler;
import io.opaa.connection.consent.PendingConsentSweep;
import io.opaa.connection.consent.SourceConsentService;
import io.opaa.connection.oauth.ConnectionAuthorizationService.LibraryConsent;
import io.opaa.connection.oauth.ConnectionAuthorizationService.Started;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.EffectiveSourceSettings;
import io.opaa.connection.profile.ProfileRegistrations;
import io.opaa.indexing.source.SecretKind;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.consentprobe.ConsentProbeIndexingExecutor;
import io.opaa.indexing.source.consentprobe.ConsentProbeSourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.organization.Organization;
import io.opaa.security.CredentialsEncryptor;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.FakeAuthorizationServer;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * "Quelle verbinden" (#2169) against the shared fake authorization server, through the API: a
 * library's own OAuth consent with a service account - confirmed before any redirect, given in the
 * wizard before the library exists and taken over by it, handed to the run through the core, bound
 * to the library and its managers, outlasting the person who gave it, and ended on every way - a
 * refusal of the provider, a disconnection, the library's deletion and a changed registration -
 * revoked and told to those responsible. No token, code or state appears in an answer, a log line
 * or a protocol.
 */
@OpaaIntegrationTest
class SourceConsentIntegrationTest {

  private static final String ADMIN = "/api/v1/admin/connection-profiles";
  private static final String AUTHORIZATIONS = "/api/v1/connections/authorizations";
  private static final String BASE = "https://opaa.example.org";
  private static final String SERVER = "https://consent-probe.example.org";
  private static final String CLIENT_ID = "opaa-client-2169";
  private static final String CLIENT_SECRET = "client-secret-2169-geheim";
  private static final FakeAuthorizationServer PROVIDER = FakeAuthorizationServer.shared();

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ConnectionAuthorizationRepository authorizationRepository;
  @Autowired private ConnectionProfileRepository profileRepository;
  @Autowired private ProfileRegistrations registrations;
  @Autowired private ConnectedAccountService accounts;
  @Autowired private SourceConsentService consents;
  @Autowired private EffectiveSourceSettings effective;
  @Autowired private ObjectProvider<SourceConnectorRegistry> connectors;
  @Autowired private CredentialsEncryptor encryptor;
  @Autowired private TargetAddressValidator targetAddressValidator;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private ConsentProbeIndexingExecutor probe;
  @Autowired private ConnectionLifecycleReconciler reconciler;
  @Autowired private ConnectionExpiryWatch expiryWatch;
  @Autowired private PendingConsentSweep sweep;
  @Autowired private ConsentProbeSourceConnector probeConnector;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetShellService shellService;

  private final List<UUID> libraries = new ArrayList<>();
  private final List<UUID> extraProfiles = new ArrayList<>();
  private final List<String> answers = new ArrayList<>();
  private final List<String> sensitive = new ArrayList<>();
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private Logger root;
  private UUID profile;
  private UUID person;
  private CurrentUser caller;
  private ConnectionAuthorizationService service;

  @BeforeEach
  void aReleasedOAuthProfileForLibraries() throws Exception {
    PROVIDER.reset();
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    root.addAppender(appender);
    ((Logger) LoggerFactory.getLogger("io.opaa")).setLevel(Level.DEBUG);
    sensitive.add(CLIENT_SECRET);
    call("dev-user", get("/api/v1/spaces"), null);
    call("dev-admin", get("/api/v1/spaces"), null);
    person = userIdOf("dev-user@opaa.local");
    caller = CurrentUser.of(person, Organization.DEFAULT_ID, SystemRole.USER, "Dev User");
    String created =
        call(
            "dev-admin",
            post(ADMIN),
            """
            {"name": "Zugang Dienstkonto %s", "sourceType": "CONSENT_PROBE", "serverUrl": "%s",
             "authMethod": "OAUTH", "ownership": "LIBRARY", "clientId": "%s",
             "clientSecret": "%s"}
            """
                .formatted(UUID.randomUUID(), SERVER, CLIENT_ID, CLIENT_SECRET));
    profile = UUID.fromString(JsonPath.read(created, "$.id"));
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + profile);
    service = serviceAt(Clock.systemUTC());
  }

  @AfterEach
  void removeOwnRows() {
    root.detachAppender(appender);
    ((Logger) LoggerFactory.getLogger("io.opaa")).setLevel(null);
    PROVIDER.reset();
    jdbc.update("UPDATE users SET directory_locked_at = NULL WHERE id = ?", person);
    jdbc.update("DELETE FROM connection_person_states WHERE user_id = ?", person);
    for (UUID library : libraries) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", library.toString());
      jdbc.update("DELETE FROM asset_grant_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", library);
      jdbc.update("DELETE FROM source_sync_state WHERE library_id = ?", library);
    }
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    for (UUID other : extraProfiles) {
      jdbc.update("DELETE FROM connection_tokens WHERE profile_id = ?", other);
      jdbc.update("DELETE FROM connection_authorizations WHERE profile_id = ?", other);
      jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", other);
      ConnectorReleases.withdraw(jdbc, "PROFILE:" + other);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", other.toString());
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", other);
    }
    jdbc.update("DELETE FROM connection_tokens WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connection_authorizations WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
    ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
    jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
    jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
  }

  /** Acceptance criterion of #2169: without the confirmation no redirect starts. */
  @Test
  void withoutTheConfirmationOfAServiceAccountNoConsentStarts() throws Exception {
    mockMvc
        .perform(
            as("dev-user", post(AUTHORIZATIONS))
                .content(
                    "{\"profileId\": \"%s\", \"purpose\": \"LIBRARY_NEW\"}".formatted(profile)))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.code")
                .value(ConnectionAuthorizationService.SERVICE_ACCOUNT_CONFIRMATION_REQUIRED));
    assertThatThrownBy(
            () ->
                service.start(
                    caller,
                    profile,
                    ConnectionAuthorizationPurpose.LIBRARY_NEW,
                    new LibraryConsent(null, false, null, false)))
        .isInstanceOfSatisfying(
            ValidationException.class,
            e ->
                assertThat(e.getCode())
                    .isEqualTo(
                        ConnectionAuthorizationService.SERVICE_ACCOUNT_CONFIRMATION_REQUIRED));

    assertThat(authorizationRows()).isZero();
  }

  @Test
  void theWizardConnectsBeforeTheLibraryExistsAndTheNewLibraryTakesTheConsentOver()
      throws Exception {
    String completed = completeNew("dev-user");

    assertThat((String) JsonPath.read(completed, "$.returnTo")).isEqualTo("/libraries/new");
    assertThat((String) JsonPath.read(completed, "$.pendingConnection.accountLabel"))
        .isEqualTo(FakeAuthorizationServer.DEFAULT_ACCOUNT);
    UUID pending = UUID.fromString(JsonPath.read(completed, "$.pendingConnection.id"));
    String access = PROVIDER.lastToken();
    sensitive.addAll(List.of(access, PROVIDER.lastRefreshToken()));
    assertThat(
            jdbc.queryForObject(
                "SELECT pending_user_id FROM connection_tokens WHERE id = ?", UUID.class, pending))
        .isEqualTo(person);

    String browse =
        """
        {"sourceUrl": "%s", "connectionProfileId": "%s", "pendingConnectionId": "%s"}
        """
            .formatted(SERVER, profile, pending);
    String listed = call("dev-user", post(browseUrl()), browse);
    assertThat((String) JsonPath.read(listed, "$.entries[0].key"))
        .isEqualTo(ConsentProbeSourceConnector.LISTED_FOLDER);
    String foreign = call("dev-admin", post(browseUrl()), browse);
    assertThat((List<?>) JsonPath.read(foreign, "$.entries")).as("only its person").isEmpty();

    UUID library = createLibrary("dev-user", pending);

    String detail = call("dev-user", get("/api/v1/libraries/" + library), null);
    assertThat((String) JsonPath.read(detail, "$.sourceConnection.accountLabel"))
        .isEqualTo(FakeAuthorizationServer.DEFAULT_ACCOUNT);
    assertThat((String) JsonPath.read(detail, "$.sourceConnection.responsible.type"))
        .isEqualTo("USER");
    assertThat((String) JsonPath.read(detail, "$.sourceConnection.responsible.id"))
        .isEqualTo(person.toString());
    assertThat(
            jdbc.queryForMap(
                "SELECT library_id, pending_user_id, pending_account_label FROM connection_tokens"
                    + " WHERE id = ?",
                pending))
        .containsEntry("library_id", library)
        .containsEntry("pending_user_id", null)
        .containsEntry("pending_account_label", null);
    assertThat(logEntries(library)).containsExactly("CONNECTED:null:" + accountLabel());

    run("dev-user", library);

    assertThat(probe.tokensSeenBy(library))
        .singleElement()
        .satisfies(
            seen -> {
              assertThat(seen.kind()).isEqualTo(SecretKind.ACCESS_TOKEN);
              assertThat(seen.value()).isEqualTo(access);
            });
    assertNothingLeaked(library);
  }

  @Test
  void onlyThePersonWhoGaveItHandsAPendingConsentToANewLibrary() throws Exception {
    UUID pending =
        UUID.fromString(JsonPath.read(completeNew("dev-user"), "$.pendingConnection.id"));

    mockMvc
        .perform(as("dev-admin", post("/api/v1/libraries")).content(libraryJson(pending)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(SourceConsentService.PENDING_CONNECTION_UNUSABLE));

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM library_connections WHERE profile_id = ?",
                Integer.class,
                profile))
        .as("the refused library was not created")
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT pending_user_id FROM connection_tokens WHERE id = ?", UUID.class, pending))
        .isEqualTo(person);
  }

  /** Acceptance criterion of #2169: the consenting person's deactivation leaves the connection. */
  @Test
  void theDeactivationOfThePersonWhoConsentedLeavesTheSourceConnection() throws Exception {
    UUID library = connectedLibrary();
    // the library is run on by a colleague who manages it too
    call(
        "dev-user",
        post("/api/v1/assets/KNOWLEDGE_LIBRARY/" + library + "/grants"),
        "{\"subjectType\": \"USER\", \"subjectId\": \"%s\", \"role\": \"MANAGER\"}"
            .formatted(userIdOf("admin@opaa.local")));
    jdbc.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", person);

    reconciler.reconcile(List.of(person));
    run("dev-admin", library);

    assertThat(probe.tokensSeenBy(library))
        .singleElement()
        .satisfies(seen -> assertThat(seen.value()).isEqualTo(PROVIDER.lastToken()));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM connection_tokens WHERE library_id = ?",
                Integer.class,
                library))
        .isEqualTo(1);
    mockMvc
        .perform(as("dev-admin", get("/api/v1/libraries/" + library)))
        .andExpect(jsonPath("$.sourceBlock").doesNotExist())
        .andExpect(jsonPath("$.sourceConnection.endedCause").doesNotExist());
  }

  @Test
  void aGrantTheProviderNoLongerTakesEndsTheConnectionAndTellsWhoIsResponsible() throws Exception {
    UUID library = connectedLibrary();
    nearItsEnd(library);
    PROVIDER.rejectWith(400, "invalid_grant");

    run("dev-user", library, "FAILED");

    mockMvc
        .perform(as("dev-user", get("/api/v1/libraries/" + library)))
        .andExpect(jsonPath("$.sourceBlock.reason").value("EXPIRED"))
        .andExpect(jsonPath("$.sourceBlock.action").value("CONNECT_SOURCE"))
        .andExpect(jsonPath("$.sourceConnection.endedCause").value("PROVIDER_REJECTED"));
    assertThat(logEntries(library)).contains("EXPIRED:PROVIDER_REJECTED:" + accountLabel());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type = 'SOURCE_CONNECTION_EXPIRED'"
                    + " AND recipient_user_id = ? AND object_id = ?",
                Integer.class,
                person,
                library))
        .isEqualTo(1);
    String dormant = call("dev-admin", get("/api/v1/admin/source-connections/dormant"), null);
    assertThat((List<String>) JsonPath.read(dormant, "$[*].libraryId"))
        .contains(library.toString());
    mockMvc
        .perform(as("dev-user", get("/api/v1/admin/source-connections/dormant")))
        .andExpect(status().isForbidden());

    PROVIDER.accept();
    reconnect("dev-user", library, false).andExpect(status().isOk());
    mockMvc
        .perform(as("dev-user", get("/api/v1/libraries/" + library)))
        .andExpect(jsonPath("$.sourceBlock").doesNotExist())
        .andExpect(jsonPath("$.sourceConnection.endedCause").doesNotExist());
  }

  @Test
  void reconnectingAsAnotherAccountNeedsAConfirmationAndThenDiscardsTheRunState() throws Exception {
    UUID library = connectedLibrary();
    jdbc.update(
        "INSERT INTO source_sync_state (id, library_id, updated_at) VALUES (?, ?, now())",
        UUID.randomUUID(),
        library);
    PROVIDER.account("anderes-konto@example.org");

    reconnect("dev-user", library, false)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(SourceConsentService.ACCOUNT_CHANGED))
        .andExpect(jsonPath("$.error").value(containsString("anderes-konto@example.org")));

    String refused = PROVIDER.lastRefreshToken();
    assertThat(PROVIDER.revocations())
        .as("the fresh grant is revoked")
        .anySatisfy(request -> assertThat(request.form()).containsEntry("token", refused));
    assertThat(accountLabelOf(library)).isEqualTo(FakeAuthorizationServer.DEFAULT_ACCOUNT);
    assertThat(syncStates(library)).isEqualTo(1);

    String answer =
        reconnect("dev-user", library, true)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat((String) JsonPath.read(answer, "$.returnTo")).isEqualTo("/libraries/" + library);
    assertThat(accountLabelOf(library)).isEqualTo("anderes-konto@example.org");
    assertThat(syncStates(library)).isZero();
    assertThat(logEntries(library)).contains("RECONNECTED:null:anderes-konto@example.org");
  }

  @Test
  void aConsentIsBoundToItsPersonAndItsLibraryWhichOnlyItsManagersConnect() throws Exception {
    UUID library = connectedLibrary();

    mockMvc
        .perform(
            as("dev-user", post(AUTHORIZATIONS))
                .content(
                    ("{\"profileId\": \"%s\", \"purpose\": \"LIBRARY_RECONNECT\","
                            + " \"libraryId\": \"%s\", \"serviceAccountConfirmed\": true}")
                        .formatted(profile, UUID.randomUUID())))
        .andExpect(status().isNotFound());
    UUID foreign = createLibraryWithoutConsent("dev-admin");
    assertThatThrownBy(
            () ->
                service.start(
                    caller,
                    profile,
                    ConnectionAuthorizationPurpose.LIBRARY_RECONNECT,
                    new LibraryConsent(foreign, true, null, false)))
        .hasMessageContaining("nicht gefunden");

    Started started =
        service.start(
            caller,
            profile,
            ConnectionAuthorizationPurpose.LIBRARY_RECONNECT,
            new LibraryConsent(library, true, null, false));
    complete("dev-admin", started).andExpect(status().isNotFound());

    assertThat(PROVIDER.requests("authorization_code")).hasSize(1);
  }

  @Test
  void disconnectingRevokesTheGrantAndTheLibraryRestsUntilItIsConnectedAnew() throws Exception {
    UUID library = connectedLibrary();
    String refresh = PROVIDER.lastRefreshToken();

    mockMvc
        .perform(as("dev-user", delete("/api/v1/libraries/" + library + "/source-connection")))
        .andExpect(status().isNoContent());

    assertThat(PROVIDER.revocations())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.form()).containsEntry("token", refresh);
              assertThat(request.authorization()).isEqualTo(basic(CLIENT_ID, CLIENT_SECRET));
            });
    mockMvc
        .perform(as("dev-user", get("/api/v1/libraries/" + library)))
        .andExpect(jsonPath("$.sourceBlock.reason").value("NOT_CONNECTED"))
        .andExpect(jsonPath("$.sourceBlock.action").value("CONNECT_SOURCE"))
        .andExpect(jsonPath("$.sourceConnection.endedCause").value("SELF"));
    assertThat(logEntries(library)).contains("DISCONNECTED:SELF:" + accountLabel());

    reconnect("dev-user", library, false).andExpect(status().isOk());
    mockMvc
        .perform(as("dev-user", get("/api/v1/libraries/" + library)))
        .andExpect(jsonPath("$.sourceBlock").doesNotExist());
  }

  @Test
  void deletingTheLibraryRevokesItsConsent() throws Exception {
    UUID library = connectedLibrary();
    String refresh = PROVIDER.lastRefreshToken();

    mockMvc
        .perform(as("dev-user", delete("/api/v1/libraries/" + library)))
        .andExpect(status().isNoContent());

    assertThat(PROVIDER.revocations())
        .singleElement()
        .satisfies(request -> assertThat(request.form()).containsEntry("token", refresh));
    assertThat(logEntries(library)).contains("DELETED:LIBRARY_DELETED:" + accountLabel());
  }

  @Test
  void aChangedRegistrationCountsTheConsentDiscardsAndRevokesItAndTellsWhoIsResponsible()
      throws Exception {
    UUID library = connectedLibrary();
    String change =
        """
        {"name": "Zugang Dienstkonto neu %s", "serverUrl": "%s", "authMethod": "OAUTH",
         "ownership": "LIBRARY", "clientId": "andere-app", "clientSecret": "anderes-geheimnis"%s}
        """;

    mockMvc
        .perform(
            as("dev-admin", post(ADMIN + "/" + profile + "/impact"))
                .content(change.formatted(UUID.randomUUID(), SERVER, "")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connectionsDiscarded").value(1))
        .andExpect(jsonPath("$.confirmation").value(containsString("1 Verbindung")));
    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + profile))
                .content(change.formatted(UUID.randomUUID(), SERVER, "")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value(containsString("1 Verbindung")));
    call(
        "dev-admin",
        put(ADMIN + "/" + profile),
        change.formatted(UUID.randomUUID(), SERVER, ", \"confirmDiscard\": true"));

    assertThat(PROVIDER.revocations())
        .singleElement()
        .satisfies(
            request ->
                assertThat(request.authorization()).isEqualTo(basic(CLIENT_ID, CLIENT_SECRET)));
    mockMvc
        .perform(as("dev-user", get("/api/v1/libraries/" + library)))
        .andExpect(jsonPath("$.sourceBlock.reason").value("NOT_CONNECTED"))
        .andExpect(jsonPath("$.sourceConnection.endedCause").value("REGISTRATION_CHANGED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type = 'SOURCE_CONNECTION_ENDED'"
                    + " AND recipient_user_id = ? AND object_id = ?",
                Integer.class,
                person,
                library))
        .isEqualTo(1);
  }

  @Test
  void whoIsResponsibleIsWarnedOnceBeforeTheConsentEnds() throws Exception {
    UUID library = connectedLibrary();
    jdbc.update(
        "UPDATE connection_tokens SET expires_at = now() + interval '10 days',"
            + " expiry_warned_at = NULL WHERE library_id = ?",
        library);

    expiryWatch.warn();
    expiryWatch.warn();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type = 'SOURCE_CONNECTION_EXPIRING'"
                    + " AND recipient_user_id = ? AND object_id = ?",
                Integer.class,
                person,
                library))
        .isEqualTo(1);
    mockMvc
        .perform(as("dev-user", get("/api/v1/libraries/" + library)))
        .andExpect(jsonPath("$.sourceConnection.expiresAt").exists());
  }

  @Test
  void aPendingConsentNoLibraryTookOverIsRevokedOnceItsTimeIsUp() throws Exception {
    UUID pending =
        UUID.fromString(JsonPath.read(completeNew("dev-user"), "$.pendingConnection.id"));
    String refresh = PROVIDER.lastRefreshToken();
    assertThat(sweep.sweep()).as("still waiting for its library").isZero();

    jdbc.update(
        "UPDATE connection_tokens SET pending_expires_at = now() - interval '1 second'"
            + " WHERE id = ?",
        pending);
    sweep.sweep();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM connection_tokens WHERE id = ?", Integer.class, pending))
        .isZero();
    assertThat(PROVIDER.revocations())
        .singleElement()
        .satisfies(request -> assertThat(request.form()).containsEntry("token", refresh));
    mockMvc
        .perform(as("dev-user", post("/api/v1/libraries")).content(libraryJson(pending)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(SourceConsentService.PENDING_CONNECTION_UNUSABLE));
  }

  /** Acceptance criterion of #2169: the list of resting source connections names no private one. */
  @Test
  void theListOfRestingSourceConnectionsNamesNoPrivateLibrary() throws Exception {
    UUID shared = connectedLibrary();
    mockMvc
        .perform(as("dev-user", delete("/api/v1/libraries/" + shared + "/source-connection")))
        .andExpect(status().isNoContent());
    UUID personProfile =
        UUID.fromString(
            JsonPath.read(
                call(
                    "dev-admin",
                    post(ADMIN),
                    """
                    {"name": "Zugang Personen %s", "sourceType": "OAUTH_PROBE",
                     "serverUrl": "https://oauth-probe.example.org", "authMethod": "OAUTH",
                     "ownership": "PERSON", "clientId": "%s", "clientSecret": "%s"}
                    """
                        .formatted(UUID.randomUUID(), CLIENT_ID, CLIENT_SECRET)),
                "$.id"));
    UUID privateLibrary = privateLibraryWithoutSecret(personProfile);
    try {
      String dormant = call("dev-admin", get("/api/v1/admin/source-connections/dormant"), null);

      List<String> listed = JsonPath.read(dormant, "$[*].libraryId");
      assertThat(listed).contains(shared.toString()).doesNotContain(privateLibrary.toString());
      mockMvc
          .perform(as("dev-user", get("/api/v1/libraries/" + privateLibrary)))
          .andExpect(jsonPath("$.sourceBlock.reason").value("NOT_CONNECTED"));
    } finally {
      jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", personProfile);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", personProfile.toString());
      libraryFixtures.removeLibraries(privateLibrary);
      libraries.remove(privateLibrary);
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", personProfile);
    }
  }

  /**
   * A private library of the person on {@code personProfile}, connected and once consented like a
   * shared one, whose account holds no secret: its source is not reached.
   */
  private UUID privateLibraryWithoutSecret(UUID personProfile) {
    jdbc.update(
        "INSERT INTO connected_accounts (id, organization_id, user_id, profile_id, state,"
            + " connected_at, version) VALUES (?, ?, ?, ?, 'CONNECTED', now(), 0)",
        UUID.randomUUID(),
        Organization.DEFAULT_ID,
        person,
        personProfile);
    UUID id =
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  KnowledgeLibrary saved =
                      libraryRepository.save(
                          KnowledgeLibrary.ownerOnly(
                              Organization.DEFAULT_ID,
                              "Meine Ablage " + UUID.randomUUID(),
                              null,
                              person,
                              SourceType.of("OAUTH_PROBE"),
                              null,
                              "https://oauth-probe.example.org/ablage",
                              null,
                              null,
                              false));
                  shellService.registerCreated(
                      saved, person, Map.of("name", saved.getName(), "sourceType", "OAUTH_PROBE"));
                  return saved.getId();
                });
    libraries.add(id);
    jdbc.update(
        "INSERT INTO library_connections (library_id, profile_id, created_at, updated_at,"
            + " version, connected_at, account_label) VALUES (?, ?, now(), now(), 0, now(),"
            + " 'privat@example.org')",
        id,
        personProfile);
    return id;
  }

  /**
   * Regression guard: a fresh grant goes back to the provider whatever fails after the exchange.
   */
  @Test
  void aFailingAccountLookupRevokesTheFreshGrantAndAnswersWithoutAnInternalError()
      throws Exception {
    probeConnector.failNextAccountLookup();
    Started started =
        service.start(
            caller,
            profile,
            ConnectionAuthorizationPurpose.LIBRARY_NEW,
            new LibraryConsent(null, true, null, false));

    complete("dev-user", started).andExpect(status().isBadRequest());

    String refresh = PROVIDER.lastRefreshToken();
    assertThat(PROVIDER.revocations())
        .singleElement()
        .satisfies(request -> assertThat(request.form()).containsEntry("token", refresh));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM connection_tokens WHERE profile_id = ?",
                Integer.class,
                profile))
        .isZero();
  }

  @Test
  void aPendingConsentProbesOnlyThroughTheProfileItWasGivenOn() throws Exception {
    UUID other = secondProfile();
    UUID pending =
        UUID.fromString(JsonPath.read(completeNew("dev-user"), "$.pendingConnection.id"));
    String browse =
        """
        {"sourceUrl": "%s", "connectionProfileId": "%s", "pendingConnectionId": "%s"}
        """;

    String foreign = call("dev-user", post(browseUrl()), browse.formatted(SERVER, other, pending));
    String own = call("dev-user", post(browseUrl()), browse.formatted(SERVER, profile, pending));

    assertThat((List<?>) JsonPath.read(foreign, "$.entries")).as("another profile").isEmpty();
    assertThat((String) JsonPath.read(own, "$.entries[0].key"))
        .isEqualTo(ConsentProbeSourceConnector.LISTED_FOLDER);
  }

  @Test
  void movingTheLibraryToAnotherProfileRevokesItsConsentWithTheOldRegistration() throws Exception {
    UUID library = connectedLibrary();
    String refresh = PROVIDER.lastRefreshToken();
    UUID other = secondProfile();

    call(
        "dev-user",
        put("/api/v1/libraries/" + library + "/connection-profile"),
        "{\"profileId\": \"%s\"}".formatted(other));

    assertRevokedWithTheOldRegistration(refresh);
    assertThat(consentRows(library)).isZero();
    assertThat(logEntries(library)).contains("DISCONNECTED:SELF:" + accountLabel());
    assertThat(accountLabelOf(library)).as("the connection forgets the consent").isNull();
  }

  /**
   * A connector signing in by OAuth requires profiles, so a library on it is never released to an
   * address of its own: the release is refused and the consent stays.
   */
  @Test
  void aLibraryWithItsOwnConsentIsNotReleasedFromItsProfile() throws Exception {
    UUID library = connectedLibrary();
    ConnectorReleases.releaseToAllAccounts(jdbc, "TYPE:" + ConsentProbeSourceConnector.TYPE.key());
    try {
      mockMvc
          .perform(as("dev-user", delete("/api/v1/libraries/" + library + "/connection-profile")))
          .andExpect(status().isBadRequest());
    } finally {
      ConnectorReleases.withdraw(jdbc, "TYPE:" + ConsentProbeSourceConnector.TYPE.key());
    }

    assertThat(PROVIDER.revocations()).isEmpty();
    assertThat(consentRows(library)).isEqualTo(1);
  }

  @Test
  void theEmergencyShutdownRevokesTheConsentAndTellsWhoIsResponsible() throws Exception {
    UUID library = connectedLibrary();
    String refresh = PROVIDER.lastRefreshToken();

    call("dev-admin", post(ADMIN + "/" + profile + "/disconnect-all"), null);

    assertRevokedWithTheOldRegistration(refresh);
    assertThat(consentRows(library)).isZero();
    assertThat(logEntries(library)).contains("EMERGENCY_DISCONNECTED:EMERGENCY:" + accountLabel());
    mockMvc
        .perform(as("dev-user", get("/api/v1/libraries/" + library)))
        .andExpect(jsonPath("$.sourceBlock.reason").value("NOT_CONNECTED"))
        .andExpect(jsonPath("$.sourceConnection.endedCause").value("EMERGENCY"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type = 'SOURCE_CONNECTION_ENDED'"
                    + " AND recipient_user_id = ? AND object_id = ?",
                Integer.class,
                person,
                library))
        .isEqualTo(1);
  }

  @Test
  void deletingTheProfileRevokesTheConsentWithTheRegistrationItWasIssuedTo() throws Exception {
    UUID library = connectedLibrary();
    String refresh = PROVIDER.lastRefreshToken();

    mockMvc
        .perform(as("dev-admin", delete(ADMIN + "/" + profile)))
        .andExpect(status().isNoContent());

    assertRevokedWithTheOldRegistration(refresh);
    assertThat(consentRows(library)).isZero();
    assertThat(logEntries(library)).contains("DELETED:PROFILE_DELETED:" + accountLabel());
    mockMvc
        .perform(as("dev-user", get("/api/v1/libraries/" + library)))
        .andExpect(jsonPath("$.connectionProfileRemoved").value(true))
        .andExpect(jsonPath("$.sourceConnection.endedCause").value("PROFILE_DELETED"));
  }

  /** Account, responsible and end of a library's consent are for those who manage it. */
  @Test
  void aReaderOfTheLibrarySeesNeitherTheAccountNorWhoIsResponsible() throws Exception {
    UUID admin = userIdOf("admin@opaa.local");
    CurrentUser adminCaller =
        CurrentUser.of(admin, Organization.DEFAULT_ID, SystemRole.SYSTEM_ADMIN, "Dev Admin");
    Started started =
        service.start(
            adminCaller,
            profile,
            ConnectionAuthorizationPurpose.LIBRARY_NEW,
            new LibraryConsent(null, true, null, false));
    UUID pending =
        UUID.fromString(
            JsonPath.read(
                complete("dev-admin", started)
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString(StandardCharsets.UTF_8),
                "$.pendingConnection.id"));
    UUID library = createLibrary("dev-admin", pending);
    call(
        "dev-admin",
        post("/api/v1/assets/KNOWLEDGE_LIBRARY/" + library + "/grants"),
        "{\"subjectType\": \"USER\", \"subjectId\": \"%s\", \"role\": \"VIEWER\"}"
            .formatted(person));

    String read = call("dev-user", get("/api/v1/libraries/" + library), null);
    String managed = call("dev-admin", get("/api/v1/libraries/" + library), null);

    assertThat((Object) JsonPath.read(read, "$.sourceConnection")).isNull();
    assertThat(read).doesNotContain(accountLabel());
    assertThat((String) JsonPath.read(managed, "$.sourceConnection.accountLabel"))
        .isEqualTo(accountLabel());
  }

  /** A second profile of the probe at the same server address, released to all accounts. */
  private UUID secondProfile() throws Exception {
    UUID other =
        UUID.fromString(
            JsonPath.read(
                call(
                    "dev-admin",
                    post(ADMIN),
                    """
                    {"name": "Zugang Dienstkonto zwei %s", "sourceType": "CONSENT_PROBE",
                     "serverUrl": "%s", "authMethod": "OAUTH", "ownership": "LIBRARY",
                     "clientId": "zweite-app-2169", "clientSecret": "zweites-geheimnis"}
                    """
                        .formatted(UUID.randomUUID(), SERVER)),
                "$.id"));
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + other);
    extraProfiles.add(other);
    return other;
  }

  /** The consent's refresh token went back once, proven with the registration it came from. */
  private void assertRevokedWithTheOldRegistration(String refresh) {
    assertThat(PROVIDER.revocations())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.form()).containsEntry("token", refresh);
              assertThat(request.authorization()).isEqualTo(basic(CLIENT_ID, CLIENT_SECRET));
            });
  }

  private int consentRows(UUID library) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM connection_tokens WHERE library_id = ?", Integer.class, library);
  }

  /** A shared library on the profile, its source connected in the wizard by the person. */
  private UUID connectedLibrary() throws Exception {
    UUID pending =
        UUID.fromString(JsonPath.read(completeNew("dev-user"), "$.pendingConnection.id"));
    return createLibrary("dev-user", pending);
  }

  private String completeNew(String user) throws Exception {
    Started started =
        service.start(
            caller,
            profile,
            ConnectionAuthorizationPurpose.LIBRARY_NEW,
            new LibraryConsent(null, true, null, false));
    return complete(user, started)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  private ResultActions reconnect(String user, UUID library, boolean acceptsAccountChange)
      throws Exception {
    return complete(
        user,
        service.start(
            caller,
            profile,
            ConnectionAuthorizationPurpose.LIBRARY_RECONNECT,
            new LibraryConsent(library, true, null, acceptsAccountChange)));
  }

  private ResultActions complete(String user, Started started) throws Exception {
    URI url = started.authorizationUrl();
    String code = PROVIDER.approve(url);
    String state = query(url).get("state");
    sensitive.addAll(List.of(code, state));
    ResultActions result =
        mockMvc.perform(
            as(user, post(AUTHORIZATIONS + "/complete"))
                .content("{\"state\": \"%s\", \"code\": \"%s\"}".formatted(state, code)));
    answers.add(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    return result;
  }

  private UUID createLibrary(String user, UUID pending) throws Exception {
    String created = call(user, post("/api/v1/libraries"), libraryJson(pending));
    UUID library = UUID.fromString(JsonPath.read(created, "$.id"));
    libraries.add(library);
    return library;
  }

  private UUID createLibraryWithoutConsent(String user) throws Exception {
    String created =
        call(
            user,
            post("/api/v1/libraries"),
            """
            {"name": "Ohne Verbindung %s", "sourceType": "CONSENT_PROBE",
             "connectionProfileId": "%s"}
            """
                .formatted(UUID.randomUUID(), profile));
    UUID library = UUID.fromString(JsonPath.read(created, "$.id"));
    libraries.add(library);
    return library;
  }

  private String libraryJson(UUID pending) {
    return """
        {"name": "Bauamt %s", "sourceType": "CONSENT_PROBE", "connectionProfileId": "%s",
         "pendingConnectionId": "%s"}
        """
        .formatted(UUID.randomUUID(), profile, pending);
  }

  private void run(String user, UUID library) throws Exception {
    run(user, library, "COMPLETED");
  }

  private void run(String user, UUID library, String outcome) throws Exception {
    mockMvc
        .perform(as(user, post("/api/v1/libraries/" + library + "/indexing")))
        .andExpect(status().isAccepted());
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                mockMvc
                    .perform(as(user, get("/api/v1/libraries/" + library + "/indexing/status")))
                    .andExpect(jsonPath("$.status").value(outcome)));
  }

  /** The stored access token lasts one more minute of an hour: within the renewal margin. */
  private void nearItsEnd(UUID library) {
    jdbc.update(
        "UPDATE connection_tokens SET access_token_expires_at = now() + interval '1 minute',"
            + " updated_at = now() - interval '1 hour' WHERE library_id = ?",
        library);
  }

  private List<String> logEntries(UUID library) {
    return jdbc.queryForList(
        "SELECT event_type || ':' || coalesce(cause, 'null') || ':' || coalesce(account_label, '')"
            + " FROM connection_log WHERE profile_id = ? AND library_id = ? AND owner_kind ="
            + " 'LIBRARY' ORDER BY recorded_at",
        String.class,
        profile,
        library);
  }

  private static String accountLabel() {
    return FakeAuthorizationServer.DEFAULT_ACCOUNT;
  }

  private String accountLabelOf(UUID library) {
    return jdbc.queryForObject(
        "SELECT account_label FROM library_connections WHERE library_id = ?",
        String.class,
        library);
  }

  private int syncStates(UUID library) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM source_sync_state WHERE library_id = ?", Integer.class, library);
  }

  private int authorizationRows() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM connection_authorizations WHERE profile_id = ?",
        Integer.class,
        profile);
  }

  /** No token, code, state or client secret in an answer, a log line or a protocol. */
  private void assertNothingLeaked(UUID library) {
    List<String> lines = new ArrayList<>(answers);
    for (ILoggingEvent event : appender.list) {
      lines.add(event.getFormattedMessage());
      if (event.getThrowableProxy() != null) {
        lines.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
      }
    }
    for (UUID object : List.of(profile, library)) {
      lines.addAll(
          jdbc.queryForList(
              "SELECT coalesce(before, '') || coalesce(after, '') FROM audit_log"
                  + " WHERE object_id = ?",
              String.class,
              object.toString()));
    }
    lines.addAll(
        jdbc.queryForList(
            "SELECT row_to_json(l)::text FROM connection_log l WHERE profile_id = ?",
            String.class,
            profile));
    lines.addAll(
        jdbc.queryForList(
            "SELECT coalesce(title, '') || coalesce(body, '') FROM notifications"
                + " WHERE object_id = ?",
            String.class,
            library));
    for (String value : sensitive) {
      if (value != null) {
        assertThat(lines).noneSatisfy(line -> assertThat(line).contains(value));
      }
    }
  }

  private ConnectionAuthorizationService serviceAt(Clock at) {
    return new ConnectionAuthorizationService(
        authorizationRepository,
        profileRepository,
        registrations,
        accounts,
        consents,
        effective,
        connectors,
        new PublicBaseUrl(new PublicBaseUrlProperties(BASE)),
        encryptor,
        targetAddressValidator,
        transactionManager,
        at);
  }

  private static String browseUrl() {
    return "/api/v1/source-types/" + ConsentProbeSourceConnector.TYPE.key() + "/browse";
  }

  private String call(String user, MockHttpServletRequestBuilder request, String body)
      throws Exception {
    MockHttpServletRequestBuilder built = as(user, request);
    if (body != null) {
      built.content(body);
    }
    String answer =
        mockMvc
            .perform(built)
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isBetween(200, 299))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    answers.add(answer);
    return answer;
  }

  private UUID userIdOf(String email) {
    return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
  }

  private static String basic(String id, String secret) {
    return "Basic "
        + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
  }

  private static Map<String, String> query(URI url) {
    Map<String, String> query = new LinkedHashMap<>();
    for (String pair : url.getRawQuery().split("&")) {
      int equals = pair.indexOf('=');
      query.put(
          URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
    }
    return query;
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
