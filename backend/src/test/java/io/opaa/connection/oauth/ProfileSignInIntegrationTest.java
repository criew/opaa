package io.opaa.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.opaa.auth.DevAuthFilter;
import io.opaa.indexing.source.ServiceAccountKeyFixture;
import io.opaa.indexing.source.profileprobe.ProfileKeyProbeIndexingExecutor;
import io.opaa.indexing.source.profileprobe.ProfileKeyProbeSourceConnector;
import io.opaa.indexing.source.profileprobe.ProfileProbeIndexingExecutor.Seen;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.FakeAuthorizationServer;
import io.opaa.test.FakeAuthorizationServer.Request;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * A library on a profile that holds a service account key (#2220), through the API and a real run:
 * the run signs with the profile's key and the connector sees the token alone; key and token appear
 * in no answer, log line or audit entry; a rejected key blocks every library of the profile without
 * asking the provider again until a sign-in test lifts it; a changed imitated account needs a
 * confirmation naming the libraries, discards their run state and tells their managers.
 */
@OpaaIntegrationTest
class ProfileSignInIntegrationTest {

  private static final String ADMIN = "/api/v1/admin/connection-profiles";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeAuthorizationServer SERVER = FakeAuthorizationServer.shared();

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private ProfileKeyProbeIndexingExecutor probe;
  @Autowired private ProfileKeyProbeSourceConnector connector;

  private final ServiceAccountKeyFixture key = new ServiceAccountKeyFixture();
  private final List<UUID> libraries = new ArrayList<>();
  private final List<String> answers = new ArrayList<>();
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private Logger root;
  private UUID profile;
  private Instant start;

  @BeforeEach
  void setUp() throws Exception {
    start = Instant.now();
    SERVER.reset();
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    root.addAppender(appender);
    ((Logger) LoggerFactory.getLogger("io.opaa")).setLevel(Level.DEBUG);
    String created =
        call(
            "dev-admin",
            post(ADMIN),
            """
            {"name": "Zugang Schlüssel %s", "sourceType": "PROFILE_KEY_PROBE", "serverUrl": "",
             "authMethod": "SERVICE_ACCOUNT_KEY", "ownership": "LIBRARY", "clientSecret": %s,
             "connectorSettings": {"subject": "fach@example.org"}}
            """
                .formatted(UUID.randomUUID(), JSON.writeValueAsString(key.json())));
    profile = UUID.fromString(JsonPath.read(created, "$.id"));
    assertThat((String) JsonPath.read(created, "$.clientId"))
        .isEqualTo(ServiceAccountKeyFixture.CLIENT_EMAIL);
    assertThat((Boolean) JsonPath.read(created, "$.clientSecretSet")).isTrue();
    assertThat((Boolean) JsonPath.read(created, "$.signInRejected")).isFalse();
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + profile);
  }

  @AfterEach
  void tearDown() {
    root.detachAppender(appender);
    ((Logger) LoggerFactory.getLogger("io.opaa")).setLevel(null);
    SERVER.reset();
    for (UUID library : libraries) {
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", library);
    }
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
    jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
    jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
  }

  @Test
  void aRunSignsWithTheProfilesKeyAndTheKeyLeaksNowhere() throws Exception {
    UUID library = library();
    call("dev-user", get("/api/v1/libraries/" + library), null);

    Seen seen = run(library, "COMPLETED");

    assertThat(seen.credentials()).isEqualTo(SERVER.lastToken());
    assertThat(seen.settings().sourceCredentials()).isNull();
    assertThat(seen.settings().connectorSettings().get("subject")).isEqualTo("fach@example.org");
    Request request = SERVER.requests().getLast();
    SignedJWT assertion = SignedJWT.parse(request.form().get("assertion"));
    assertThat(assertion.verify(new RSASSAVerifier(key.publicKey()))).isTrue();
    assertThat(assertion.getJWTClaimsSet().getIssuer())
        .isEqualTo(ServiceAccountKeyFixture.CLIENT_EMAIL);
    assertThat(assertion.getJWTClaimsSet().getSubject()).isEqualTo("fach@example.org");

    call("dev-admin", get(ADMIN + "/" + profile), null);
    call("dev-admin", get(ADMIN), null);
    call("dev-admin", post(ADMIN + "/" + profile + "/test-sign-in"), null);
    String marker = key.privateKeyMarker();
    assertThat(answers)
        .allSatisfy(answer -> assertThat(answer).doesNotContain(marker, "private_key"));
    List<String> audit =
        jdbc.queryForList(
            "SELECT coalesce(before, '') || coalesce(after, '') FROM audit_log"
                + " WHERE recorded_at >= ?",
            String.class,
            Timestamp.from(start));
    assertThat(audit).isNotEmpty().allSatisfy(entry -> assertThat(entry).doesNotContain(marker));
    assertThat(appender.list)
        .allSatisfy(
            event -> {
              String throwable =
                  event.getThrowableProxy() == null
                      ? ""
                      : ThrowableProxyUtil.asString(event.getThrowableProxy());
              assertThat(event.getFormattedMessage() + throwable)
                  .doesNotContain(marker, "private_key", SERVER.lastToken());
            });
  }

  @Test
  void anImitatedAccountOfTheLibraryUnderTheProfileIsRefused() throws Exception {
    mockMvc
        .perform(
            as("dev-user", post("/api/v1/libraries"))
                .content(
                    """
                    {"name": "Eigenes Konto %s", "sourceType": "PROFILE_KEY_PROBE",
                     "connectionProfileId": "%s", "sourceSettings": {"subject": "chef@example.org"}}
                    """
                        .formatted(UUID.randomUUID(), profile)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("subject")));
  }

  /** {@code invalid_grant} for the key: every library is blocked, no run asks the provider. */
  @Test
  void aRejectedKeyBlocksEveryLibraryWithoutAskingAgainUntilASignInTestLiftsIt() throws Exception {
    UUID first = library();
    UUID second = library();
    SERVER.rejectWith(400, "invalid_grant");

    run(first, "FAILED");
    int asked = SERVER.requests().size();
    run(second, "FAILED");
    run(first, "FAILED");

    assertThat(asked).isEqualTo(1);
    assertThat(SERVER.requests()).hasSize(1);
    assertThat(lastRunMessage(second)).contains("Abgelaufen", "Systemverwaltung");
    assertThat(
            (Boolean)
                JsonPath.read(
                    call("dev-admin", get(ADMIN + "/" + profile), null), "$.signInRejected"))
        .isTrue();

    String refused = call("dev-admin", post(ADMIN + "/" + profile + "/test-sign-in"), null);
    SERVER.accept();
    String accepted = call("dev-admin", post(ADMIN + "/" + profile + "/test-sign-in"), null);

    assertThat((Boolean) JsonPath.read(refused, "$.success")).isFalse();
    assertThat((Boolean) JsonPath.read(accepted, "$.success")).isTrue();
    assertThat(run(second, "COMPLETED").credentials()).isEqualTo(SERVER.lastToken());
    // both tests stand in the audit with their outcome, without the provider's message
    assertThat(
            jdbc.queryForList(
                "SELECT outcome FROM audit_log WHERE object_id = ? AND event_type ="
                    + " 'CONNECTION_PROFILE_SIGN_IN_TESTED' ORDER BY recorded_at",
                String.class,
                profile.toString()))
        .containsExactly("FAILURE", "SUCCESS");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE object_id = ? AND event_type ="
                    + " 'CONNECTION_PROFILE_SIGN_IN_TESTED' AND after::text LIKE '%invalid_grant%'",
                Integer.class, profile.toString()))
        .isZero();
  }

  @Test
  void aChangedImitatedAccountNamesTheLibrariesDiscardsTheirRunStateAndTellsTheirManagers()
      throws Exception {
    UUID library = library();
    String change =
        """
        {"name": "Zugang Schlüssel %s", "serverUrl": "", "authMethod": "SERVICE_ACCOUNT_KEY",
         "ownership": "LIBRARY", "connectorSettings": {"subject": "neu@example.org"}%s}
        """;

    String preview =
        call("dev-admin", post(ADMIN + "/" + profile + "/impact"), change.formatted(profile, ""));
    mockMvc
        .perform(as("dev-admin", put(ADMIN + "/" + profile)).content(change.formatted(profile, "")))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.error")
                .value(org.hamcrest.Matchers.containsString("von 1 Bibliothek wird verworfen")));
    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + profile))
                .content(change.formatted(profile, ", \"confirmDiscard\": true")))
        .andExpect(status().isOk());

    assertThat((Integer) JsonPath.read(preview, "$.fullSyncLibraries")).isEqualTo(1);
    assertThat(connector.changedSettingsOf(library)).contains("subject");
    UUID owner =
        jdbc.queryForObject("SELECT id FROM users WHERE email = 'dev-user@opaa.local'", UUID.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND object_id = ?"
                    + " AND type = 'SOURCE_FULL_SYNC_FORCED'",
                Integer.class,
                owner,
                library))
        .isEqualTo(1);
    run(library, "COMPLETED");
    assertThat(
            SignedJWT.parse(SERVER.requests().getLast().form().get("assertion"))
                .getJWTClaimsSet()
                .getSubject())
        .isEqualTo("neu@example.org");
  }

  /** A new key of another account is a new registration; the emergency shutdown drops the key. */
  @Test
  void aKeyOfAnotherAccountChangesTheRegistrationAndTheShutdownDropsTheKey() throws Exception {
    library();
    ServiceAccountKeyFixture other =
        new ServiceAccountKeyFixture("anderes@opaa-test.iam.gserviceaccount.com");
    String change =
        """
        {"name": "Zugang Schlüssel %s", "serverUrl": "", "authMethod": "SERVICE_ACCOUNT_KEY",
         "ownership": "LIBRARY", "clientSecret": %s,
         "connectorSettings": {"subject": "fach@example.org"}%s}
        """;

    mockMvc
        .perform(
            as("dev-admin", put(ADMIN + "/" + profile))
                .content(change.formatted(profile, JSON.writeValueAsString(other.json()), "")))
        .andExpect(status().isConflict());
    String changed =
        call(
            "dev-admin",
            put(ADMIN + "/" + profile),
            change.formatted(
                profile, JSON.writeValueAsString(other.json()), ", \"confirmDiscard\": true"));
    String shutDown = call("dev-admin", post(ADMIN + "/" + profile + "/disconnect-all"), null);

    assertThat((String) JsonPath.read(changed, "$.clientId"))
        .isEqualTo("anderes@opaa-test.iam.gserviceaccount.com");
    assertThat(shutDown).doesNotContain(other.privateKeyMarker());
    assertThat(
            (Boolean)
                JsonPath.read(
                    call("dev-admin", get(ADMIN + "/" + profile), null), "$.clientSecretSet"))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE object_id = ? AND event_type ="
                    + " 'CONNECTION_PROFILE_DISCONNECTED' AND replace(after::text, ' ', '') LIKE"
                    + " '%\"clientSecretDeleted\":true%'",
                Integer.class, profile.toString()))
        .isEqualTo(1);
  }

  /**
   * An imitated account removed from the profile does not pass to the libraries as their own: none
   * carries one, and the next run imitates no one.
   */
  @Test
  void anImitatedAccountRemovedFromTheProfileIsNotKeptByTheLibraries() throws Exception {
    UUID library = library();
    String change =
        """
        {"name": "Zugang Schlüssel %s", "serverUrl": "", "authMethod": "SERVICE_ACCOUNT_KEY",
         "ownership": "LIBRARY", "confirmDiscard": true}
        """;

    call("dev-admin", put(ADMIN + "/" + profile), change.formatted(profile));

    String own =
        jdbc.queryForObject(
            "SELECT coalesce(source_settings::text, '') FROM knowledge_libraries WHERE id = ?",
            String.class,
            library);
    assertThat(own).doesNotContain("subject");
    Seen seen = run(library, "COMPLETED");
    assertThat(
            seen.settings().connectorSettings() == null
                ? null
                : seen.settings().connectorSettings().get("subject"))
        .isNull();
    assertThat(
            SignedJWT.parse(SERVER.requests().getLast().form().get("assertion"))
                .getJWTClaimsSet()
                .getSubject())
        .isNull();
  }

  /**
   * A changed imitated account would end a running listing with the state of two accounts: it is
   * refused while a library on the profile runs.
   */
  @Test
  void aChangedImitatedAccountIsRefusedWhileALibraryRuns() throws Exception {
    UUID library = library();
    jdbc.update(
        "INSERT INTO indexing_jobs (id, status, run_mode, triggered_by, last_progress_at,"
            + " library_id, organization_id) SELECT gen_random_uuid(), 'RUNNING', 'FULL',"
            + " 'MANUAL', now(), id, organization_id FROM knowledge_libraries WHERE id = ?",
        library);
    String change =
        """
        {"name": "Zugang Schlüssel %s", "serverUrl": "", "authMethod": "SERVICE_ACCOUNT_KEY",
         "ownership": "LIBRARY", "connectorSettings": {"subject": "neu@example.org"},
         "confirmDiscard": true}
        """;

    mockMvc
        .perform(as("dev-admin", put(ADMIN + "/" + profile)).content(change.formatted(profile)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONNECTION_PROFILE_RUN_IN_PROGRESS"));

    assertThat(connector.changedSettingsOf(library)).doesNotContain("subject");
    jdbc.update("DELETE FROM indexing_jobs WHERE library_id = ?", library);
  }

  private UUID library() throws Exception {
    String body =
        call(
            "dev-user",
            post("/api/v1/libraries"),
            """
            {"name": "Schlüsselbibliothek %s", "sourceType": "PROFILE_KEY_PROBE",
             "connectionProfileId": "%s"}
            """
                .formatted(UUID.randomUUID(), profile));
    UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
    libraries.add(id);
    assertThat((Boolean) JsonPath.read(body, "$.sourceCredentialsSet")).isFalse();
    return id;
  }

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
    return JsonPath.read(
        call("dev-user", get("/api/v1/libraries/" + library + "/indexing/status"), null),
        "$.message");
  }

  private String call(String user, MockHttpServletRequestBuilder request, String body)
      throws Exception {
    request.header(DevAuthFilter.DEV_USER_HEADER, user);
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    String answer =
        mockMvc
            .perform(request)
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    answers.add(answer);
    return answer;
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
