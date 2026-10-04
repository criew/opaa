package io.opaa.connection.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.account.LocalAccountAccessEndedEvent;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.UserRepository;
import io.opaa.connection.account.ConnectionLifecycleReconciler.Outcome;
import io.opaa.connection.profile.SecretTarget;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.SecretOwner.PersonOwned;
import io.opaa.connection.token.SecretRefusedException;
import io.opaa.indexing.source.SourceBlock.Reason;
import io.opaa.indexing.source.profileprobe.PersonProbeSourceConnector;
import io.opaa.organization.Organization;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The lifecycle of a person's connections against the Liquibase schema (ADR-0041, Entscheidung 4):
 * only a deactivated account ends its connections and deletes its secrets; a resting one keeps
 * everything and goes on after the next sign-in; a handover or a changed group ends nothing; the
 * reconciliation after commit and at start writes its log entries in a transaction of its own.
 */
@OpaaIntegrationTest
class ConnectionLifecycleIntegrationTest {

  private static final String ME = "/api/v1/me/connected-accounts";
  private static final String ADMIN = "/api/v1/admin/connection-profiles";
  private static final String SERVER = "https://lifecycle.example.org";
  private static final String TARGET = new SecretTarget(SERVER, null).key();
  private static final String SECRET = "avogt:" + PersonProbeSourceConnector.ACCEPTED_PASSWORD;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ConnectionSecrets secrets;
  @Autowired private ConnectionLifecycleReconciler reconciler;
  @Autowired private ConnectionLifecycle lifecycle;
  @Autowired private UserRepository users;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private PlatformTransactionManager transactionManager;

  private UUID profile;
  private UUID person;
  private UUID group;

  @BeforeEach
  void aConnectedPerson() throws Exception {
    mockMvc.perform(as("dev-user", get("/api/v1/spaces"))).andExpect(status().isOk());
    person =
        jdbc.queryForObject(
            "SELECT id FROM users WHERE email = ?", UUID.class, "dev-user@opaa.local");
    restorePerson();
    String body =
        mockMvc
            .perform(
                as("dev-admin", post(ADMIN))
                    .content(
                        """
                        {"name": "Lebenszyklus %s", "sourceType": "PERSON_PROBE",
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
                .content(
                    "{\"username\": \"avogt\", \"secret\": \"%s\"}"
                        .formatted(PersonProbeSourceConnector.ACCEPTED_PASSWORD)))
        .andExpect(status().isOk());
  }

  @AfterEach
  void removeOwnRows() {
    restorePerson();
    if (group != null) {
      jdbc.update("DELETE FROM group_memberships WHERE group_id = ?", group);
      jdbc.update("DELETE FROM groups WHERE id = ?", group);
    }
    jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connection_person_states WHERE user_id = ?", person);
    ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
    jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
    jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
  }

  /** Acceptance criterion of #2163: after a deactivation no secret of the person exists. */
  @Test
  void aDeactivationAfterCommitDeletesTheSecretAndLogsInATransactionOfItsOwn() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              jdbc.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", person);
              events.publishEvent(
                  LocalAccountAccessEndedEvent.bySystem(
                      users.findById(person).orElseThrow(), "test"));
            });

    assertThat(tokenRows()).isZero();
    assertThat(accountRows()).isZero();
    assertRefused(Reason.OWNER_DEACTIVATED);
    // written by the listener after the commit: lost unless it opened its own transaction
    List<Map<String, Object>> entries = logEntries();
    assertThat(entries)
        .extracting(
            entry -> entry.get("event_type"),
            entry -> entry.get("cause"),
            entry -> entry.get("owner_kind"))
        .containsExactly(
            tuple("CONNECTED", null, "PERSON"), tuple("DELETED", "ACCOUNT_DEACTIVATED", "PERSON"));
    assertThat(entries.get(1).get("actor_ref")).isEqualTo("SYSTEM");
    assertThat(lifecycle.deactivatedSince(person)).isPresent();
    assertThat(lifecycle.deactivatedBefore(Instant.now().plusSeconds(60))).containsKey(person);
  }

  @Test
  void restingDeletesNothingAndTheNextSignInGoesOnWithoutReconnecting() {
    jdbc.update(
        "UPDATE users SET last_login_at = now() - interval '200 days' WHERE id = ?", person);

    Outcome outcome = reconciler.reconcile(List.of(person));

    assertThat(outcome.dormant()).isEqualTo(1);
    assertThat(outcome.connectionsEnded()).isZero();
    assertThat(tokenRows()).isEqualTo(1);
    assertThat(stateOfAccount()).isEqualTo("CONNECTED");
    assertRefused(Reason.DORMANT);
    assertThat(dormantSince()).isNotNull();
    assertThat(lifecycle.deactivatedSince(person)).isEmpty();

    jdbc.update("UPDATE users SET last_login_at = now() WHERE id = ?", person);
    reconciler.reconcile(List.of(person));

    assertThat(dormantSince()).isNull();
    assertThat(secrets.current(new PersonOwned(profile, person), TARGET).value()).isEqualTo(SECRET);
  }

  /** Deactivation is not absence: a handover and a changed group leave the connection. */
  @Test
  void aHandoverAndAChangedGroupEndNothing() {
    group = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'AD_HOC', ?)",
        group,
        Organization.DEFAULT_ID,
        "Versetzt " + group);
    jdbc.update(
        "INSERT INTO group_memberships (id, user_id, group_id, organization_id, created_at)"
            + " VALUES (?, ?, ?, ?, now())",
        UUID.randomUUID(),
        person,
        group,
        Organization.DEFAULT_ID);
    // what a redeemed handover publishes: the person acted, the account stays usable
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                events.publishEvent(
                    LocalAccountAccessEndedEvent.by(users.findById(person).orElseThrow(), person)));
    reconciler.reconcileAll();

    assertThat(tokenRows()).isEqualTo(1);
    assertThat(stateOfAccount()).isEqualTo("CONNECTED");
    assertThat(logEntries())
        .extracting(entry -> entry.get("event_type"))
        .containsExactly("CONNECTED");
    assertThat(lifecycle.deactivatedSince(person)).isEmpty();
    assertThat(secrets.current(new PersonOwned(profile, person), TARGET).value()).isEqualTo(SECRET);
  }

  /**
   * After a restored backup the start reconciliation deletes the secrets of accounts deactivated
   * meanwhile - without any event - and counts the expired ones.
   */
  @Test
  void theStartReconciliationDeletesSecretsOfDeactivatedAccountsAndCountsExpiredOnes() {
    long expiredBefore = secrets.countExpiredPersonSecrets();
    jdbc.update(
        "UPDATE connection_tokens SET expires_at = now() - interval '1 day' WHERE profile_id = ?",
        profile);
    assertThat(secrets.countExpiredPersonSecrets()).isEqualTo(expiredBefore + 1);
    jdbc.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", person);

    reconciler.afterStart();

    assertThat(tokenRows()).isZero();
    assertThat(accountRows()).isZero();
    assertThat(secrets.countExpiredPersonSecrets()).isEqualTo(expiredBefore);
    assertThat(lifecycle.deactivatedSince(person)).isPresent();

    // a second run finds nothing left to end and keeps the start of the deactivation
    Instant since = lifecycle.deactivatedSince(person).orElseThrow();
    Outcome again = reconciler.reconcile(List.of(person));
    assertThat(again.secretsDeleted()).isZero();
    assertThat(lifecycle.deactivatedSince(person)).contains(since);
  }

  /** The use is recorded coarsely, also from a read-only transaction. */
  @Test
  void aHandOutRecordsItsUseAtMostOncePerDay() {
    PersonOwned owner = new PersonOwned(profile, person);
    TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);

    readOnly.executeWithoutResult(status -> secrets.current(owner, TARGET));
    Instant first = lastUsedAt();
    assertThat(first).isNotNull();

    Instant earlier = first.minus(1, ChronoUnit.HOURS);
    jdbc.update(
        "UPDATE connected_accounts SET last_used_at = ? WHERE profile_id = ?",
        Timestamp.from(earlier),
        profile);
    secrets.current(owner, TARGET);
    assertThat(lastUsedAt()).isEqualTo(earlier.truncatedTo(ChronoUnit.MICROS));

    jdbc.update(
        "UPDATE connected_accounts SET last_used_at = now() - interval '2 days'"
            + " WHERE profile_id = ?",
        profile);
    secrets.current(owner, TARGET);
    assertThat(lastUsedAt()).isAfter(Instant.now().minus(1, ChronoUnit.HOURS));
  }

  private void assertRefused(Reason reason) {
    assertThatThrownBy(() -> secrets.current(new PersonOwned(profile, person), TARGET))
        .isInstanceOfSatisfying(
            SecretRefusedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
  }

  private void restorePerson() {
    jdbc.update(
        "UPDATE users SET directory_locked_at = NULL, last_login_at = now() WHERE id = ?", person);
  }

  private int tokenRows() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM connection_tokens WHERE profile_id = ?", Integer.class, profile);
  }

  private int accountRows() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM connected_accounts WHERE profile_id = ?", Integer.class, profile);
  }

  private String stateOfAccount() {
    return jdbc.queryForObject(
        "SELECT state FROM connected_accounts WHERE profile_id = ?", String.class, profile);
  }

  private Instant lastUsedAt() {
    Timestamp at =
        jdbc.queryForObject(
            "SELECT last_used_at FROM connected_accounts WHERE profile_id = ?",
            Timestamp.class,
            profile);
    return at == null ? null : at.toInstant();
  }

  private Timestamp dormantSince() {
    return jdbc.queryForObject(
        "SELECT dormant_since FROM connection_person_states WHERE user_id = ?",
        Timestamp.class,
        person);
  }

  private List<Map<String, Object>> logEntries() {
    return jdbc.queryForList(
        "SELECT event_type, cause, actor_ref, owner_kind FROM connection_log WHERE profile_id = ?"
            + " ORDER BY recorded_at",
        profile);
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }
}
