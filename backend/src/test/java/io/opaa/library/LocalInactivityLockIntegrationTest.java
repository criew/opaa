package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.account.LocalAccountAccessEndedEvent;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.UserRepository;
import io.opaa.connection.account.ConnectedAccountService;
import io.opaa.connection.account.ConnectionLifecycle;
import io.opaa.connection.account.ConnectionLifecycleReconciler;
import io.opaa.connection.profile.SecretTarget;
import io.opaa.connection.profile.SourceBlocks;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.SecretOwner.PersonOwned;
import io.opaa.connection.token.SecretRefusedException;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceBlock.Reason;
import io.opaa.indexing.source.profileprobe.PersonProbeSourceConnector;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The inactivity lock of a local account is an absence, not a deactivation (#2260): the connection
 * and its secret stay, the private library rests without a deletion day, and once the account is
 * unlocked and signs in again it goes on without reconnecting. A lock by the administration still
 * deactivates. The lock is applied as the maintenance does - committed, then the event.
 */
@OpaaIntegrationTest
class LocalInactivityLockIntegrationTest {

  private static final String SERVER = "https://inaktiv.example.org";
  private static final String TARGET = new SecretTarget(SERVER, null).key();

  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private PrivateLibraryCreation privateCreation;
  @Autowired private PrivateLibraryDeletionRun deletionRun;
  @Autowired private ConnectionLifecycleReconciler reconciler;
  @Autowired private ConnectionLifecycle lifecycle;
  @Autowired private ConnectedAccountService accounts;
  @Autowired private ConnectionSecrets secrets;
  @Autowired private SourceBlocks blocks;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private UserRepository users;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private PlatformTransactionManager transactionManager;

  private UUID profile;
  private UUID person;
  private UUID library;

  @BeforeEach
  void aLocalOwnerWithAPrivateLibrary() {
    profile = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO connection_profiles (id, name, source_type, server_url, auth_method,"
            + " ownership, created_at, updated_at, version)"
            + " VALUES (?, ?, 'PERSON_PROBE', ?, 'PERSONAL_SECRET', 'PERSON', now(), now(), 0)",
        profile,
        "Zugang Inaktivität " + profile,
        SERVER);
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + profile);
    person = UUID.randomUUID();
    // a system administrator passes the switch of the local management, which is off here
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, organization_id,"
            + " system_role, last_login_at)"
            + " VALUES (?, ?, 'urn:opaa:local', ?, 'Lokale Besitzerin', ?, 'SYSTEM_ADMIN', now())",
        person,
        "inaktiv-" + person,
        "inaktiv-" + person + "@example.com",
        Organization.DEFAULT_ID);
    jdbc.update(
        "INSERT INTO local_credentials (user_id, password_hash, created_reason,"
            + " email_verified_at) VALUES (?, '{noop}x', 'Test', now())",
        person);
    CurrentUser caller =
        CurrentUser.of(person, Organization.DEFAULT_ID, SystemRole.SYSTEM_ADMIN, "Besitzerin");
    accounts.connect(caller, profile, "besitzerin", PersonProbeSourceConnector.ACCEPTED_PASSWORD);
    library =
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
            caller);
    assertThat(secrets.current(owner(), TARGET).value()).isNotBlank();
  }

  @AfterEach
  void removeOwnRows() {
    if (library != null) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", library.toString());
      jdbc.update("DELETE FROM asset_grant_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", library);
      libraryFixtures.removeLibraries(library);
    }
    jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
    ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
    jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    if (person != null) {
      jdbc.update("DELETE FROM connection_person_states WHERE user_id = ?", person);
      jdbc.update("DELETE FROM asset_ownership_history WHERE owner_user_id = ?", person);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", person.toString());
      jdbc.update("DELETE FROM notifications WHERE recipient_user_id = ?", person);
      jdbc.update("DELETE FROM local_credentials WHERE user_id = ?", person);
      jdbc.update("DELETE FROM users WHERE id = ?", person);
    }
  }

  @Test
  void anInactivityLockLetsTheConnectionRestAndKeepsTheSecret() {
    lockedAfterCommit("INACTIVITY", "200 days");

    assertThat(tokenRows()).isEqualTo(1);
    assertThat(stateOfAccount()).isEqualTo("CONNECTED");
    assertRefused(Reason.DORMANT);
    assertThat(lifecycle.deactivatedSince(person)).isEmpty();
    SourceBlock block = blockOfLibrary().orElseThrow();
    assertThat(block.reason()).isEqualTo(Reason.DORMANT);
    assertThat(block.contentDeletedOn()).isNull();
    assertThat(block.notice()).doesNotContain("Löschfrist").doesNotContain("gelöscht");
    jdbc.update(
        "UPDATE connection_person_states SET dormant_since = now() - interval '365 days'"
            + " WHERE user_id = ?",
        person);
    assertThat(deletionRun.runOnce().erased()).isZero();
    assertThat(libraryRepository.existsById(library)).isTrue();
  }

  @Test
  void afterUnlockAndTheNextSignInItGoesOnWithoutReconnecting() {
    lockedAfterCommit("INACTIVITY", "200 days");

    jdbc.update(
        "UPDATE local_credentials SET locked_at = NULL, locked_reason = NULL WHERE user_id = ?",
        person);
    jdbc.update("UPDATE users SET last_login_at = now() WHERE id = ?", person);
    reconciler.reconcile(List.of(person));

    assertThat(secrets.current(owner(), TARGET).value())
        .isEqualTo("besitzerin:" + PersonProbeSourceConnector.ACCEPTED_PASSWORD);
    assertThat(blockOfLibrary()).isEmpty();
    assertThat(dormantSince()).isZero();
  }

  /** The explicit lock by the administration still ends the connection and dates the deletion. */
  @Test
  void aLockByTheAdministrationStillDeactivates() {
    lockedAfterCommit("ADMIN", "1 day");

    assertThat(tokenRows()).isZero();
    assertRefused(Reason.OWNER_DEACTIVATED);
    assertThat(lifecycle.deactivatedSince(person)).isPresent();
    SourceBlock block = blockOfLibrary().orElseThrow();
    assertThat(block.reason()).isEqualTo(Reason.OWNER_DEACTIVATED);
    assertThat(block.contentDeletedOn()).isNotNull();
  }

  private void lockedAfterCommit(String reason, String lastSignInAgo) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              jdbc.update(
                  "UPDATE users SET last_login_at = now() - interval '"
                      + lastSignInAgo
                      + "'"
                      + " WHERE id = ?",
                  person);
              jdbc.update(
                  "UPDATE local_credentials SET locked_at = now(), locked_reason = ?"
                      + " WHERE user_id = ?",
                  reason,
                  person);
              events.publishEvent(
                  LocalAccountAccessEndedEvent.bySystem(
                      users.findById(person).orElseThrow(), "local-auth"));
            });
  }

  private Optional<SourceBlock> blockOfLibrary() {
    return blocks.blockOf(libraryRepository.findById(library).orElseThrow(), SourceBlocks.ALL);
  }

  private PersonOwned owner() {
    return new PersonOwned(profile, person);
  }

  private void assertRefused(Reason reason) {
    assertThatThrownBy(() -> secrets.current(owner(), TARGET))
        .isInstanceOfSatisfying(
            SecretRefusedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
  }

  private int tokenRows() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM connection_tokens WHERE profile_id = ?", Integer.class, profile);
  }

  private String stateOfAccount() {
    return jdbc.queryForObject(
        "SELECT state FROM connected_accounts WHERE profile_id = ?", String.class, profile);
  }

  private int dormantSince() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM connection_person_states WHERE user_id = ?"
            + " AND dormant_since IS NOT NULL",
        Integer.class,
        person);
  }
}
