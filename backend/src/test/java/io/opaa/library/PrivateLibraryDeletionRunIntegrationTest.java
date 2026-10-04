package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.connection.account.ConnectedAccountService;
import io.opaa.connection.account.ConnectionLifecycleReconciler;
import io.opaa.connection.profile.SourceBlocks;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceBlock.Reason;
import io.opaa.indexing.source.profileprobe.PersonProbeSourceConnector;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The deletion run of private libraries (#2165): only a deactivation that lasted longer than the
 * deletion period of 30 days and still holds erases; a reactivated owner, a resting one, an absent
 * one and a changed group keep their libraries. Until then the block names the day.
 */
@OpaaIntegrationTest
class PrivateLibraryDeletionRunIntegrationTest {

  private static final String SERVER = "https://person.example.org";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private PrivateLibraryCreation privateCreation;
  @Autowired private PrivateLibraryDeletionRun deletionRun;
  @Autowired private ConnectionLifecycleReconciler reconciler;
  @Autowired private ConnectedAccountService accounts;
  @Autowired private SourceBlocks blocks;
  @Autowired private KnowledgeLibraryRepository libraryRepository;

  private final List<UUID> libraries = new ArrayList<>();
  private final List<UUID> persons = new ArrayList<>();
  private UUID profile;
  private UUID group;

  @BeforeEach
  void aProfileForPersons() {
    profile = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO connection_profiles (id, name, source_type, server_url, auth_method,"
            + " ownership, created_at, updated_at, version)"
            + " VALUES (?, ?, 'PERSON_PROBE', ?, 'PERSONAL_SECRET', 'PERSON', now(), now(), 0)",
        profile,
        "Zugang Löschlauf " + profile,
        SERVER);
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + profile);
  }

  @AfterEach
  void removeOwnRows() {
    for (UUID library : libraries) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", library.toString());
      jdbc.update("DELETE FROM asset_grant_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", library);
    }
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    libraries.clear();
    jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
    ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
    jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    if (group != null) {
      jdbc.update("DELETE FROM group_memberships WHERE group_id = ?", group);
      jdbc.update("DELETE FROM groups WHERE id = ?", group);
    }
    for (UUID person : persons) {
      jdbc.update("DELETE FROM connection_person_states WHERE user_id = ?", person);
      jdbc.update("DELETE FROM asset_ownership_history WHERE owner_user_id = ?", person);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", person.toString());
      jdbc.update("DELETE FROM notifications WHERE recipient_user_id = ?", person);
      jdbc.update("DELETE FROM local_credentials WHERE user_id = ?", person);
      jdbc.update("DELETE FROM users WHERE id = ?", person);
    }
    persons.clear();
  }

  @Test
  void aDeactivationErasesOnlyOnceItOutlastedThePeriod() {
    UUID person = aPersonWithAPrivateLibrary();
    UUID library = libraries.getLast();
    deactivate(person);
    reconciler.reconcile(List.of(person));
    Instant since = backdateDeactivation(person, 29);

    assertThat(deletionRun.runOnce().erased()).isZero();
    assertThat(exists(library)).isTrue();
    SourceBlock block =
        blocks
            .blockOf(libraryRepository.findById(library).orElseThrow(), SourceBlocks.ALL)
            .orElseThrow();
    LocalDate deletedOn =
        LocalDate.ofInstant(since.plus(30, ChronoUnit.DAYS), ZoneId.systemDefault());
    assertThat(block.reason()).isEqualTo(Reason.OWNER_DEACTIVATED);
    assertThat(block.contentDeletedOn()).isEqualTo(deletedOn);
    assertThat(block.notice())
        .contains(
            "ab dem "
                + deletedOn.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")));

    backdateDeactivation(person, 31);
    PrivateLibraryDeletionRun.Result result = deletionRun.runOnce();

    assertThat(result.erased()).isEqualTo(1);
    assertThat(exists(library)).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE object_id = ?"
                    + " AND event_type = 'PRIVATE_LIBRARY_ERASED' AND actor_kind = 'SYSTEM_PROCESS'"
                    + " AND before LIKE '%DELETION_PERIOD_EXPIRED%'",
                Integer.class, library.toString()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM connected_accounts WHERE user_id = ?", Integer.class, person))
        .as("the ended connection no private library runs on any more")
        .isZero();
  }

  @Test
  void anOwnerReactivatedWithinThePeriodKeepsHerLibraries() {
    UUID person = aPersonWithAPrivateLibrary();
    deactivate(person);
    reconciler.reconcile(List.of(person));
    backdateDeactivation(person, 31);
    jdbc.update("UPDATE users SET directory_locked_at = NULL WHERE id = ?", person);

    assertThat(deletionRun.runOnce().erased()).isZero();

    assertThat(exists(libraries.getLast())).isTrue();
  }

  @Test
  void aRestingOwnerNeverLosesHerLibraries() {
    UUID person = aPersonWithAPrivateLibrary();
    jdbc.update(
        "UPDATE users SET last_login_at = now() - interval '200 days' WHERE id = ?", person);
    reconciler.reconcile(List.of(person));
    jdbc.update(
        "UPDATE connection_person_states SET dormant_since = now() - interval '365 days'"
            + " WHERE user_id = ?",
        person);

    assertThat(deletionRun.runOnce().erased()).isZero();

    assertThat(exists(libraries.getLast())).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM connection_person_states WHERE user_id = ?"
                    + " AND deactivated_since IS NOT NULL",
                Integer.class,
                person))
        .isZero();
  }

  @Test
  void anAbsenceOrAChangedGroupTriggersNothing() {
    UUID person = aPersonWithAPrivateLibrary();
    jdbc.update("UPDATE users SET last_login_at = now() - interval '45 days' WHERE id = ?", person);
    group = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'AD_HOC', ?)",
        group,
        Organization.DEFAULT_ID,
        "Referat " + group);
    jdbc.update(
        "INSERT INTO group_memberships (id, user_id, group_id, organization_id)"
            + " VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        person,
        group,
        Organization.DEFAULT_ID);
    reconciler.reconcile(List.of(person));
    jdbc.update("DELETE FROM group_memberships WHERE group_id = ?", group);
    reconciler.reconcile(List.of(person));

    assertThat(deletionRun.runOnce().erased()).isZero();

    assertThat(exists(libraries.getLast())).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM connection_person_states WHERE user_id = ?"
                    + " AND (deactivated_since IS NOT NULL OR dormant_since IS NOT NULL)",
                Integer.class,
                person))
        .isZero();
  }

  /**
   * A local account locked for inactivity is an absence, not a deactivation, until #2260 decides
   * otherwise: it starts no deletion period, names no day and erases nothing.
   */
  @Test
  void anInactivityLockOfALocalAccountStartsNoDeletionPeriod() {
    UUID person = aLocalPersonWithAPrivateLibrary();
    UUID library = libraries.getLast();
    jdbc.update(
        "UPDATE local_credentials SET locked_at = now() - interval '40 days',"
            + " locked_reason = 'INACTIVITY' WHERE user_id = ?",
        person);
    reconciler.reconcile(List.of(person));
    jdbc.update(
        "UPDATE connection_person_states SET deactivated_since = now() - interval '40 days'"
            + " WHERE user_id = ?",
        person);

    assertThat(deletionRun.runOnce().erased()).isZero();

    assertThat(exists(library)).isTrue();
    assertThat(
            blocks
                .blockOf(libraryRepository.findById(library).orElseThrow(), SourceBlocks.ALL)
                .map(SourceBlock::contentDeletedOn))
        .isEmpty();
  }

  /**
   * The period runs from the current deactivation: locked again hours ago after a reactivation the
   * daily reconciliation did not see, the recorded start of the earlier one does not count.
   */
  @Test
  void aRelockAfterAShortReactivationStartsThePeriodAnew() {
    UUID person = aPersonWithAPrivateLibrary();
    deactivate(person);
    reconciler.reconcile(List.of(person));
    backdateDeactivation(person, 31);
    jdbc.update(
        "UPDATE users SET directory_locked_at = now() - interval '2 hours' WHERE id = ?", person);

    assertThat(deletionRun.runOnce().erased()).isZero();

    assertThat(exists(libraries.getLast())).isTrue();
  }

  // -------------------------------------------------------------------------------------------

  private UUID aLocalPersonWithAPrivateLibrary() {
    UUID person = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, organization_id,"
            + " last_login_at) VALUES (?, ?, 'urn:opaa:local', ?, 'Lokale Besitzerin', ?, now())",
        person,
        "lokal-" + person,
        "lokal-" + person + "@example.com",
        Organization.DEFAULT_ID);
    jdbc.update(
        "INSERT INTO local_credentials (user_id, password_hash, created_reason)"
            + " VALUES (?, 'x', 'Test')",
        person);
    persons.add(person);
    connectAndCreate(person, "Lokale Besitzerin");
    return person;
  }

  private UUID aPersonWithAPrivateLibrary() {
    UUID person = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, organization_id,"
            + " last_login_at) VALUES (?, ?, 'opaa-dev', ?, 'Besitzerin', ?, now())",
        person,
        "loeschlauf-" + person,
        "loeschlauf-" + person + "@example.com",
        Organization.DEFAULT_ID);
    persons.add(person);
    connectAndCreate(person, "Besitzerin");
    return person;
  }

  private void connectAndCreate(UUID person, String name) {
    CurrentUser caller = CurrentUser.of(person, Organization.DEFAULT_ID, SystemRole.USER, name);
    accounts.connect(caller, profile, "besitzerin", PersonProbeSourceConnector.ACCEPTED_PASSWORD);
    libraries.add(
        privateCreation.create(
            new LibraryCreation(
                "Ablage " + UUID.randomUUID(),
                null,
                null,
                null,
                PersonProbeSourceConnector.TYPE,
                null,
                URI.create(SERVER + "/ablage"),
                null,
                null,
                null,
                null,
                null,
                profile),
            caller));
  }

  private void deactivate(UUID person) {
    jdbc.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", person);
  }

  private Instant backdateDeactivation(UUID person, int days) {
    Instant since = Instant.now().minus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    jdbc.update(
        "UPDATE connection_person_states SET deactivated_since = ? WHERE user_id = ?",
        Timestamp.from(since),
        person);
    jdbc.update(
        "UPDATE users SET directory_locked_at = ? WHERE id = ? AND directory_locked_at IS NOT NULL",
        Timestamp.from(since),
        person);
    return since;
  }

  private boolean exists(UUID library) {
    return jdbc.queryForObject(
            "SELECT count(*) FROM knowledge_libraries WHERE id = ?", Integer.class, library)
        == 1;
  }
}
