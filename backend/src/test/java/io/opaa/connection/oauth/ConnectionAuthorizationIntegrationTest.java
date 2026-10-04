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
import io.opaa.common.TooManyRequestsException;
import io.opaa.connection.account.ConnectedAccountService;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.EffectiveSourceSettings;
import io.opaa.connection.profile.ProfileRegistrations;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.SecretOwner.PersonOwned;
import io.opaa.connection.token.SecretRefusedException;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SecretKind;
import io.opaa.indexing.source.SourceBlock.Reason;
import io.opaa.indexing.source.SourceCredentialsException;
import io.opaa.indexing.source.oauthprobe.OAuthProbeIndexingExecutor;
import io.opaa.indexing.source.oauthprobe.OAuthProbeSourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.security.CredentialsEncryptor;
import io.opaa.security.TargetAddressValidator;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.FakeAuthorizationServer;
import io.opaa.test.FakeAuthorizationServer.Request;
import io.opaa.test.MutableClock;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The OAuth core (#2168) against the shared fake authorization server, through the API and the
 * store: a person's consent with state and PKCE, used once, only by them and only in time; renewal
 * before the end with a rotated refresh token, once for two asking at the same time; a refused
 * grant ending the connection; revocation after a committed discard only, with the registration it
 * was issued to; and no token, code, verifier or state in an answer, a log line or a protocol.
 */
@OpaaIntegrationTest
class ConnectionAuthorizationIntegrationTest {

  private static final String ADMIN = "/api/v1/admin/connection-profiles";
  private static final String AUTHORIZATIONS = "/api/v1/connections/authorizations";
  private static final String BASE = "https://opaa.example.org";
  private static final String SERVER = "https://oauth-probe.example.org";
  private static final String CLIENT_ID = "opaa-client-2168";
  private static final String CLIENT_SECRET = "client-secret-2168-geheim";
  private static final FakeAuthorizationServer PROVIDER = FakeAuthorizationServer.shared();

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ConnectionAuthorizationRepository authorizationRepository;
  @Autowired private ConnectionProfileRepository profileRepository;
  @Autowired private ProfileRegistrations registrations;
  @Autowired private ConnectedAccountService accounts;
  @Autowired private CredentialsEncryptor encryptor;
  @Autowired private TargetAddressValidator targetAddressValidator;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ConnectionSecrets secrets;
  @Autowired private EffectiveSourceSettings effective;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetShellService shellService;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private OAuthProbeIndexingExecutor probe;

  private final List<UUID> libraries = new ArrayList<>();
  private final List<String> answers = new ArrayList<>();
  private final List<String> sensitive = new ArrayList<>();
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private Logger root;
  private UUID profile;
  private UUID person;
  private CurrentUser caller;
  private ConnectionAuthorizationService service;
  private MutableClock clock;

  @BeforeEach
  void aReleasedOAuthProfileForPersons() throws Exception {
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
            {"name": "Zugang OAuth %s", "sourceType": "OAUTH_PROBE", "serverUrl": "%s",
             "authMethod": "OAUTH", "ownership": "PERSON", "clientId": "%s",
             "clientSecret": "%s"}
            """
                .formatted(UUID.randomUUID(), SERVER, CLIENT_ID, CLIENT_SECRET));
    profile = UUID.fromString(JsonPath.read(created, "$.id"));
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + profile);
    clock = new MutableClock(Instant.now());
    service = serviceAt(clock);
  }

  @AfterEach
  void removeOwnRows() {
    root.detachAppender(appender);
    ((Logger) LoggerFactory.getLogger("io.opaa")).setLevel(null);
    PROVIDER.reset();
    for (UUID library : libraries) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", library.toString());
      jdbc.update("DELETE FROM asset_grant_history WHERE asset_id = ?", library);
    }
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    jdbc.update("DELETE FROM connection_authorizations WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM notifications WHERE object_id = ?", profile);
    ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
    jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
    jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
  }

  /** Acceptance criteria of #2168: consent, and a test connector gets the token via the core. */
  @Test
  void aConsentConnectsTheAccountAndTheConnectorGetsItsAccessTokenThroughTheCore()
      throws Exception {
    ConnectionAuthorizationService.Started started =
        service.start(caller, profile, ConnectionAuthorizationPurpose.ACCOUNT);

    Map<String, String> query = query(started.authorizationUrl());
    assertThat(started.authorizationUrl().toString())
        .startsWith(PROVIDER.authorizationEndpoint() + "?");
    assertThat(query)
        .containsEntry("response_type", "code")
        .containsEntry("client_id", CLIENT_ID)
        .containsEntry("redirect_uri", BASE + "/connections/callback")
        .containsEntry("scope", OAuthProbeSourceConnector.SCOPES)
        .containsEntry("code_challenge_method", "S256")
        .containsEntry("access_type", "offline");
    assertThat(query.get("state")).hasSizeGreaterThanOrEqualTo(43);
    assertThat(query.get("code_challenge")).hasSize(43);
    assertThat(stateRows(ConnectionAuthorizationService.hash(query.get("state")))).isEqualTo(1);
    assertThat(stateRows(query.get("state"))).as("only the hash of the state is stored").isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT code_verifier_ciphertext FROM connection_authorizations"
                    + " WHERE profile_id = ?",
                String.class,
                profile))
        .startsWith("enc:");

    String completed =
        complete("dev-user", started)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat((String) JsonPath.read(completed, "$.returnTo")).isEqualTo("/settings/accounts");
    assertThat((String) JsonPath.read(completed, "$.purpose")).isEqualTo("ACCOUNT");
    assertThat((String) JsonPath.read(completed, "$.account.state")).isEqualTo("CONNECTED");
    Request exchange = PROVIDER.requests("authorization_code").getFirst();
    assertThat(exchange.authorization()).isEqualTo(basic(CLIENT_ID, CLIENT_SECRET));
    assertThat(exchange.form())
        .containsEntry("redirect_uri", BASE + "/connections/callback")
        .containsKey("code_verifier");
    String accessToken = PROVIDER.lastToken();
    String refreshToken = PROVIDER.lastRefreshToken();
    sensitive.addAll(List.of(accessToken, refreshToken, exchange.form().get("code_verifier")));
    assertThat(
            jdbc.queryForMap(
                "SELECT kind, secret_ciphertext, access_token_ciphertext FROM connection_tokens"
                    + " WHERE profile_id = ?",
                profile))
        .containsEntry("kind", "OAUTH")
        .allSatisfy((column, value) -> assertThat(String.valueOf(value)).doesNotContain("fake-"));

    UUID library = privateLibraryOnTheProfile();
    run(library);

    assertThat(probe.tokensSeenBy(library))
        .singleElement()
        .satisfies(
            seen -> {
              assertThat(seen.kind()).isEqualTo(SecretKind.ACCESS_TOKEN);
              assertThat(seen.value()).isEqualTo(accessToken);
            });
    assertNothingLeaked();
  }

  @Test
  void aStateCompletesOnlyOnceOnlyForItsPersonAndOnlyInTime() throws Exception {
    ConnectionAuthorizationService.Started started =
        service.start(caller, profile, ConnectionAuthorizationPurpose.ACCOUNT);

    complete("dev-admin", started).andExpect(status().isNotFound());
    complete("dev-user", started).andExpect(status().isOk());
    complete("dev-user", started).andExpect(status().isNotFound());
    assertThat(PROVIDER.requests("authorization_code")).hasSize(1);

    ConnectionAuthorizationService.Started late =
        service.start(caller, profile, ConnectionAuthorizationPurpose.ACCOUNT);
    jdbc.update(
        "UPDATE connection_authorizations SET expires_at = now() - interval '1 second'"
            + " WHERE state_hash = ?",
        ConnectionAuthorizationService.hash(query(late.authorizationUrl()).get("state")));

    complete("dev-user", late).andExpect(status().isNotFound());
    mockMvc
        .perform(
            as("dev-user", post(AUTHORIZATIONS + "/complete"))
                .content("{\"state\": \"erfunden\", \"code\": \"x\"}"))
        .andExpect(status().isNotFound());
    assertThat(PROVIDER.requests("authorization_code")).hasSize(1);
  }

  @Test
  void aChangedProfileOrARefusalAtTheProviderStoresNothing() throws Exception {
    ConnectionAuthorizationService.Started started =
        service.start(caller, profile, ConnectionAuthorizationPurpose.ACCOUNT);
    call(
        "dev-admin",
        put(ADMIN + "/" + profile),
        """
        {"name": "Zugang OAuth umbenannt %s", "serverUrl": "%s", "authMethod": "OAUTH",
         "ownership": "PERSON", "clientId": "%s"}
        """
            .formatted(UUID.randomUUID(), SERVER, CLIENT_ID));

    complete("dev-user", started)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ConnectionAuthorizationService.PROFILE_CHANGED));

    ConnectionAuthorizationService.Started refused =
        service.start(caller, profile, ConnectionAuthorizationPurpose.ACCOUNT);
    mockMvc
        .perform(
            as("dev-user", post(AUTHORIZATIONS + "/complete"))
                .content(
                    "{\"state\": \"%s\", \"error\": \"access_denied\"}"
                        .formatted(query(refused.authorizationUrl()).get("state"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(containsString("abgelehnt")));

    assertThat(PROVIDER.requests("authorization_code")).isEmpty();
    assertThat(accountRows()).isZero();
  }

  /**
   * Regression guard: a registration changed while the code is exchanged refuses the grant, and the
   * grant is revoked with the registration that obtained it, not stored for the new one.
   */
  @Test
  void aRegistrationChangedDuringTheExchangeRefusesAndRevokesTheFreshGrant() throws Exception {
    ConnectionAuthorizationService.Started started =
        service.start(caller, profile, ConnectionAuthorizationPurpose.ACCOUNT);
    PROVIDER.beforeCodeAnswer(
        () -> {
          try {
            call(
                "dev-admin",
                put(ADMIN + "/" + profile),
                """
                {"name": "Zugang OAuth neu %s", "serverUrl": "%s", "authMethod": "OAUTH",
                 "ownership": "PERSON", "clientId": "andere-app",
                 "clientSecret": "anderes-geheimnis", "confirmDiscard": true}
                """
                    .formatted(UUID.randomUUID(), SERVER));
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });

    complete("dev-user", started)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ConnectionAuthorizationService.PROFILE_CHANGED));

    assertThat(accountRows()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM connection_tokens WHERE profile_id = ?",
                Integer.class,
                profile))
        .isZero();
    assertThat(PROVIDER.revocations())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.form()).containsEntry("token", PROVIDER.lastRefreshToken());
              assertThat(request.authorization()).isEqualTo(basic(CLIENT_ID, CLIENT_SECRET));
            });
  }

  @Test
  void withoutAPublicAddressNoConsentStartsAndLibrariesAreNotServedYet() throws Exception {
    mockMvc
        .perform(
            as("dev-user", post(AUTHORIZATIONS))
                .content("{\"profileId\": \"%s\", \"purpose\": \"ACCOUNT\"}".formatted(profile)))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.code").value(ConnectionAuthorizationService.PUBLIC_BASE_URL_MISSING));
    mockMvc
        .perform(
            as("dev-user", post(AUTHORIZATIONS))
                .content(
                    "{\"profileId\": \"%s\", \"purpose\": \"LIBRARY_NEW\"}".formatted(profile)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ConnectionAuthorizationService.PURPOSE_UNAVAILABLE));
    assertThatThrownBy(
            () -> service.start(caller, profile, ConnectionAuthorizationPurpose.LIBRARY_NEW))
        .hasMessageContaining("noch nicht");

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM connection_authorizations WHERE profile_id = ?",
                Integer.class,
                profile))
        .isZero();
  }

  @Test
  void aPersonStartsOnlySoManyConsentsAtOnce() {
    for (int i = 0; i < ConnectionAuthorizationService.MAX_STARTS; i++) {
      service.start(caller, profile, ConnectionAuthorizationPurpose.ACCOUNT);
    }

    assertThatThrownBy(() -> service.start(caller, profile, ConnectionAuthorizationPurpose.ACCOUNT))
        .isInstanceOf(TooManyRequestsException.class);

    clock.advance(ConnectionAuthorizationService.LIFETIME.plusSeconds(1));
    service.start(caller, profile, ConnectionAuthorizationPurpose.ACCOUNT);
  }

  @Test
  void anAccessTokenNearItsEndIsRenewedAndARotatedRefreshTokenReplacesTheStoredOne()
      throws Exception {
    connect();
    String firstAccess = PROVIDER.lastToken();
    String firstRefresh = PROVIDER.lastRefreshToken();
    String storedRefresh = storedRefreshCiphertext();
    assertThat(current().value()).isEqualTo(firstAccess);
    assertThat(PROVIDER.requests("refresh_token")).isEmpty();

    nearItsEnd();
    Secret renewed = current();

    assertThat(renewed.value()).isNotEqualTo(firstAccess).isEqualTo(PROVIDER.lastToken());
    assertThat(PROVIDER.requests("refresh_token"))
        .singleElement()
        .satisfies(
            request -> assertThat(request.form()).containsEntry("refresh_token", firstRefresh));
    assertThat(PROVIDER.isLive(firstRefresh)).isFalse();
    assertThat(storedRefreshCiphertext()).isNotEqualTo(storedRefresh);
    assertThat(current().value()).isEqualTo(renewed.value());
    assertThat(PROVIDER.requests("refresh_token")).hasSize(1);

    String rotated = PROVIDER.lastRefreshToken();
    nearItsEnd();
    current();
    assertThat(PROVIDER.requests("refresh_token").get(1).form())
        .as("the rotated refresh token was stored with the renewal")
        .containsEntry("refresh_token", rotated);
    sensitive.addAll(
        List.of(firstAccess, firstRefresh, rotated, renewed.value(), PROVIDER.lastToken()));
    assertNothingLeaked();
  }

  @Test
  void twoAskingAtOnceRenewOnce() throws Exception {
    connect();
    nearItsEnd();
    PROVIDER.delay(Duration.ofMillis(600));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Secret> first = pool.submit(this::current);
      Future<Secret> second = pool.submit(this::current);

      assertThat(first.get().value()).isEqualTo(second.get().value());
    } finally {
      pool.shutdownNow();
    }
    assertThat(PROVIDER.requests("refresh_token")).hasSize(1);
  }

  @Test
  void aGrantTheProviderNoLongerTakesEndsTheConnection() throws Exception {
    connect();
    nearItsEnd();
    PROVIDER.rejectWith(400, "invalid_grant");

    assertRefused(Reason.EXPIRED);
    assertThat(accountState()).isEqualTo("EXPIRED");
    assertThat(
            jdbc.queryForList(
                "SELECT event_type || ':' || cause FROM connection_log WHERE profile_id = ?"
                    + " AND event_type = 'EXPIRED'",
                String.class,
                profile))
        .containsExactly("EXPIRED:PROVIDER_REJECTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type = 'CONNECTION_EXPIRED'"
                    + " AND recipient_user_id = ? AND object_id = ?",
                Integer.class,
                person,
                profile))
        .isEqualTo(1);

    PROVIDER.accept();
    assertRefused(Reason.EXPIRED);
    assertThat(PROVIDER.requests("refresh_token")).hasSize(1);
  }

  @Test
  void anUnreachableProviderLeavesTheTokenInUseUntilItReallyEnds() throws Exception {
    connect();
    String access = PROVIDER.lastToken();
    nearItsEnd();
    PROVIDER.unreachable(true);

    assertThat(current().value()).isEqualTo(access);

    jdbc.update(
        "UPDATE connection_tokens SET access_token_expires_at = now() - interval '1 second'"
            + " WHERE profile_id = ?",
        profile);
    assertThatThrownBy(this::current).isInstanceOf(SourceCredentialsException.class);
    assertThat(accountState()).isEqualTo("CONNECTED");
  }

  @Test
  void aTokenTheSourceRejectedIsRenewedOnceThroughTheCore() throws Exception {
    connect();
    String access = PROVIDER.lastToken();
    UUID library = privateLibraryOnTheProfile();
    probe.rejectFirstToken(library);

    run(library);

    List<Secret> seen = probe.tokensSeenBy(library);
    assertThat(seen).hasSize(2);
    assertThat(seen.get(0).value()).isEqualTo(access);
    assertThat(seen.get(1).value()).isNotEqualTo(access).isEqualTo(PROVIDER.lastToken());
    assertThat(PROVIDER.requests("refresh_token")).hasSize(1);
  }

  @Test
  void disconnectingRevokesTheGrantOnceTheDiscardCommitted() throws Exception {
    connect();
    String refresh = PROVIDER.lastRefreshToken();

    transactions.executeWithoutResult(
        status -> {
          secrets.discard(new PersonOwned(profile, person));
          status.setRollbackOnly();
        });
    assertThat(PROVIDER.revocations()).as("a rolled back discard revokes nothing").isEmpty();

    mockMvc
        .perform(as("dev-user", delete("/api/v1/me/connected-accounts/" + profile)))
        .andExpect(status().isNoContent());

    assertThat(PROVIDER.revocations())
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.form())
                  .containsEntry("token", refresh)
                  .containsEntry("token_type_hint", "refresh_token");
              assertThat(request.authorization()).isEqualTo(basic(CLIENT_ID, CLIENT_SECRET));
            });
    assertThat(PROVIDER.isLive(refresh)).isFalse();
  }

  @Test
  void aChangedRegistrationRevokesWithTheRegistrationTheGrantWasIssuedTo() throws Exception {
    connect();

    call(
        "dev-admin",
        put(ADMIN + "/" + profile),
        """
        {"name": "Zugang OAuth neu %s", "serverUrl": "%s", "authMethod": "OAUTH",
         "ownership": "PERSON", "clientId": "andere-app", "clientSecret": "anderes-geheimnis",
         "confirmDiscard": true}
        """
            .formatted(UUID.randomUUID(), SERVER));

    assertThat(PROVIDER.revocations())
        .singleElement()
        .satisfies(
            request ->
                assertThat(request.authorization()).isEqualTo(basic(CLIENT_ID, CLIENT_SECRET)));
    assertThat(accountRows()).isZero();
  }

  private void connect() throws Exception {
    complete("dev-user", service.start(caller, profile, ConnectionAuthorizationPurpose.ACCOUNT))
        .andExpect(status().isOk());
  }

  private ResultActions complete(String user, ConnectionAuthorizationService.Started started)
      throws Exception {
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

  private Secret current() {
    return secrets.current(
        new PersonOwned(profile, person),
        effective.personTarget(profileRepository.findById(profile).orElseThrow()));
  }

  private void assertRefused(Reason reason) {
    assertThatThrownBy(this::current)
        .isInstanceOfSatisfying(
            SecretRefusedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
  }

  /** The stored access token lasts one more minute of an hour: within the renewal margin. */
  private void nearItsEnd() {
    jdbc.update(
        "UPDATE connection_tokens SET access_token_expires_at = now() + interval '1 minute',"
            + " updated_at = now() - interval '1 hour' WHERE profile_id = ?",
        profile);
  }

  private String storedRefreshCiphertext() {
    return jdbc.queryForObject(
        "SELECT secret_ciphertext FROM connection_tokens WHERE profile_id = ?",
        String.class,
        profile);
  }

  private String accountState() {
    return jdbc.queryForObject(
        "SELECT state FROM connected_accounts WHERE user_id = ? AND profile_id = ?",
        String.class,
        person,
        profile);
  }

  private int accountRows() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM connected_accounts WHERE profile_id = ?", Integer.class, profile);
  }

  private int stateRows(String stateHash) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM connection_authorizations WHERE state_hash = ?",
        Integer.class,
        stateHash);
  }

  /** No token, code, verifier, state or client secret in an answer, a log line or a protocol. */
  private void assertNothingLeaked() {
    List<String> lines = new ArrayList<>(answers);
    for (ILoggingEvent event : appender.list) {
      lines.add(event.getFormattedMessage());
      if (event.getThrowableProxy() != null) {
        lines.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
      }
    }
    lines.addAll(
        jdbc.queryForList(
            "SELECT coalesce(before, '') || coalesce(after, '') FROM audit_log"
                + " WHERE object_id = ?",
            String.class,
            profile.toString()));
    lines.addAll(
        jdbc.queryForList(
            "SELECT row_to_json(l)::text FROM connection_log l WHERE profile_id = ?",
            String.class,
            profile));
    for (String value : sensitive) {
      if (value != null) {
        assertThat(lines).noneSatisfy(line -> assertThat(line).contains(value));
      }
    }
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
                          OAuthProbeSourceConnector.TYPE,
                          null,
                          SERVER + "/ablage",
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
            + " version) VALUES (?, ?, now(), now(), 0)",
        id,
        profile);
    return id;
  }

  private void run(UUID library) throws Exception {
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
  }

  private ConnectionAuthorizationService serviceAt(Clock at) {
    return new ConnectionAuthorizationService(
        authorizationRepository,
        profileRepository,
        registrations,
        accounts,
        new PublicBaseUrl(new PublicBaseUrlProperties(BASE)),
        encryptor,
        targetAddressValidator,
        transactionManager,
        at);
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
