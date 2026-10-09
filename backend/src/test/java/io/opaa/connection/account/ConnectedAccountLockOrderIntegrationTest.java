package io.opaa.connection.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetShellService;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.indexing.source.SourceConnectionResolver;
import io.opaa.indexing.source.profileprobe.PersonProbeSourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The lock order of a person's connection on real Postgres: every path writing her token row and
 * her connected account takes the token row first. A reconnection or a rejected sign-in racing the
 * end of the same account therefore waits for the end instead of deadlocking with it.
 */
@OpaaIntegrationTest
class ConnectedAccountLockOrderIntegrationTest {

  private static final String ME = "/api/v1/me/connected-accounts";
  private static final String SERVER = "https://reihenfolge.example.org";
  private static final String PASSWORD = PersonProbeSourceConnector.ACCEPTED_PASSWORD;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private ConnectedAccountService accounts;
  @Autowired private SourceConnectionResolver resolver;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetShellService shellService;
  @Autowired private TransactionTemplate transactions;
  @Autowired private OwnLibraryFixtures libraryFixtures;

  private final List<UUID> libraries = new ArrayList<>();

  private UUID profile;
  private UUID person;

  @BeforeEach
  void aConnectedAccount() throws Exception {
    mockMvc.perform(as("dev-user", get("/api/v1/spaces"))).andExpect(status().isOk());
    person =
        jdbc.queryForObject(
            "SELECT id FROM users WHERE email = ?", UUID.class, "dev-user@opaa.local");
    String body =
        mockMvc
            .perform(
                as("dev-admin", post("/api/v1/admin/connection-profiles"))
                    .content(
                        """
                        {"name": "Zugang Sperrreihenfolge %s", "sourceType": "PERSON_PROBE",
                         "serverUrl": "%s", "authMethod": "PERSONAL_SECRET", "ownership": "PERSON"}
                        """
                            .formatted(UUID.randomUUID(), SERVER)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    profile = UUID.fromString(JsonPath.read(body, "$.id"));
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + profile);
    mockMvc
        .perform(
            as("dev-user", put(ME + "/" + profile))
                .content("{\"username\": \"avogt\", \"secret\": \"%s\"}".formatted(PASSWORD)))
        .andExpect(status().isOk());
  }

  @AfterEach
  void removeOwnRows() {
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    jdbc.update(
        "DELETE FROM notifications WHERE type = 'CONNECTION_EXPIRED' AND recipient_user_id = ?",
        person);
    jdbc.update("DELETE FROM connection_tokens WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
    jdbc.update(
        "DELETE FROM notifications WHERE type = 'CONNECTION_ENDED' AND object_id = ?", profile);
    ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
    jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
    jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
  }

  /**
   * Regression guard for #2428. A third transaction holds the token row while the person
   * disconnects (queued on the token row) and then reconnects. Taking the account row first, the
   * reconnection held it while queued behind the disconnection, which needed it next: a deadlock.
   * Token row first, the reconnection waits for the disconnection and connects anew.
   */
  @Test
  void aReconnectionRacingTheEndOfTheSameAccountWaitsInsteadOfDeadlocking() throws Exception {
    raceBehindTheHeldTokenRow(() -> accounts.connect(caller(), profile, "avogt", PASSWORD));

    assertThat(stateOfAccount()).isEqualTo("CONNECTED");
    assertThat(tokenRows()).isEqualTo(1);
  }

  /**
   * Regression guard for #2428: a run whose sign-in the provider rejected expires the account. It
   * loaded the account before the token row, so its flush wrote them in that order and deadlocked
   * with the disconnection queued before it. Token row first, it finds the account disconnected.
   */
  @Test
  void aRejectedSignInRacingTheEndOfTheSameAccountWaitsInsteadOfDeadlocking() throws Exception {
    KnowledgeLibrary library = privateLibraryOnTheProfile();

    raceBehindTheHeldTokenRow(() -> resolver.credentialsRejected(library));

    assertThat(stateOfAccount()).isEqualTo("DISCONNECTED");
    assertThat(tokenRows()).isZero();
  }

  /**
   * A third transaction holds the person's token row while she disconnects (queued on the token
   * row), then {@code second} starts and queues as well; only then is the row released.
   */
  private void raceBehindTheHeldTokenRow(Runnable second) throws Exception {
    CurrentUser caller = caller();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      try (PreparedStatement lock =
          holder.prepareStatement(
              "SELECT t.id FROM connection_tokens t JOIN connected_accounts a"
                  + " ON a.id = t.connected_account_id"
                  + " WHERE a.profile_id = ? AND a.user_id = ? FOR UPDATE OF t")) {
        lock.setObject(1, profile);
        lock.setObject(2, person);
        try (ResultSet rows = lock.executeQuery()) {
          assertThat(rows.next()).isTrue();
        }
      }
      int holderPid = pidOf(holder);

      Future<?> disconnection = executor.submit(() -> accounts.disconnect(caller, profile));
      awaitWaitingBehind(holderPid, 1);
      Future<?> racing = executor.submit(second);
      awaitWaitingBehind(holderPid, 2);

      holder.commit();
      disconnection.get(30, TimeUnit.SECONDS);
      racing.get(30, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
  }

  private CurrentUser caller() {
    return CurrentUser.of(person, Organization.DEFAULT_ID, SystemRole.USER, "Dev User");
  }

  private String stateOfAccount() {
    return jdbc.queryForObject(
        "SELECT state FROM connected_accounts WHERE profile_id = ? AND user_id = ?",
        String.class,
        profile,
        person);
  }

  private int tokenRows() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM connection_tokens WHERE profile_id = ?", Integer.class, profile);
  }

  /** A private library of the person on the profile, reached with her connected account. */
  private KnowledgeLibrary privateLibraryOnTheProfile() {
    KnowledgeLibrary library =
        transactions.execute(
            status -> {
              KnowledgeLibrary saved =
                  libraryRepository.save(
                      KnowledgeLibrary.ownerOnly(
                          Organization.DEFAULT_ID,
                          "Ablage Sperrreihenfolge " + UUID.randomUUID(),
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
              return saved;
            });
    libraries.add(library.getId());
    jdbc.update(
        "INSERT INTO library_connections (library_id, profile_id, created_at, updated_at,"
            + " version) VALUES (?, ?, now(), now(), 0)",
        library.getId(),
        profile);
    return library;
  }

  private static int pidOf(Connection connection) throws Exception {
    try (PreparedStatement pid = connection.prepareStatement("SELECT pg_backend_pid()");
        ResultSet rows = pid.executeQuery()) {
      rows.next();
      return rows.getInt(1);
    }
  }

  /**
   * Waits until {@code count} sessions wait for a lock that the session {@code holderPid} holds,
   * directly or queued behind another waiter for it.
   */
  private void awaitWaitingBehind(int holderPid, int count) {
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                        + " AND cardinality(pg_blocking_pids(pid)) > 0"
                        + " AND (? = ANY(pg_blocking_pids(pid)) OR EXISTS (SELECT 1 FROM"
                        + " pg_stat_activity w WHERE w.pid = ANY(pg_blocking_pids("
                        + "pg_stat_activity.pid)) AND ? = ANY(pg_blocking_pids(w.pid))))",
                    Integer.class,
                    holderPid,
                    holderPid),
            waiting -> waiting == count);
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
