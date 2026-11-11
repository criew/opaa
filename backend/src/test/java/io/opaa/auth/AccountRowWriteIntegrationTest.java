package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.SystemRole;
import io.opaa.organization.Organization;
import io.opaa.test.ForeignCommit;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnUserFixtures;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An account or a sign-in provider read in a transaction and written back after a side change keeps
 * the security state another session committed after the read: a role withdrawal, a directory lock,
 * a provider switched off. The interleaving is fixed: the foreign act commits between the read and
 * the write. A foreign act that raises the version, as every versioned write does, makes a write of
 * the same field or a saved detached instance fail instead of writing back what it read.
 */
@OpaaIntegrationTest
class AccountRowWriteIntegrationTest {

  @Autowired private UserRepository users;
  @Autowired private OidcProviderRepository providers;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnUserFixtures ownUserFixtures;

  private Set<UUID> foreignUserIds;
  private UUID providerId;

  @BeforeEach
  void rememberForeignUsers() {
    foreignUserIds = ownUserFixtures.existingUserIds();
  }

  @AfterEach
  void removeOwnRows() {
    ownUserFixtures.removeUsersCreatedSince(foreignUserIds);
    if (providerId != null) {
      jdbc.update("DELETE FROM oidc_providers WHERE id = ?", providerId);
    }
  }

  @Test
  void aNameChangeDoesNotUndoARoleWithdrawalOrDirectoryLockCommittedAfterItsRead() {
    User created =
        new User(UUID.randomUUID().toString(), "row-write-issuer", "zeile@stadt.example", "Alt");
    created.setOrganizationId(Organization.DEFAULT_ID);
    created.setSystemRole(SystemRole.SYSTEM_ADMIN);
    UUID id = users.saveAndFlush(created).getId();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              User read = users.findById(id).orElseThrow();
              ForeignCommit.commit(
                  dataSource,
                  "UPDATE users SET system_role = 'USER', directory_locked_at = now() WHERE id = ?",
                  id);
              read.setDisplayName("Neu");
              users.save(read);
            });

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT system_role, directory_locked_at, display_name FROM users WHERE id = ?", id);
    assertThat(row.get("system_role")).isEqualTo("USER");
    assertThat(row.get("directory_locked_at")).isNotNull();
    assertThat(row.get("display_name")).isEqualTo("Neu");
  }

  @Test
  void aRoleWrittenFromAStaleReadFailsInsteadOfOverwritingTheRoleChangedMeanwhile() {
    UUID id = account(SystemRole.SYSTEM_ADMIN);

    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status -> {
                          User read = users.findById(id).orElseThrow();
                          ForeignCommit.commit(
                              dataSource,
                              "UPDATE users SET system_role = 'USER', version = version + 1"
                                  + " WHERE id = ?",
                              id);
                          read.setSystemRole(SystemRole.AUDITOR);
                          users.saveAndFlush(read);
                        }))
        .isInstanceOf(OptimisticLockingFailureException.class);

    assertThat(jdbc.queryForObject("SELECT system_role FROM users WHERE id = ?", String.class, id))
        .isEqualTo("USER");
  }

  @Test
  void aDetachedAccountSavedAfterARoleWithdrawalFailsInsteadOfRestoringTheRole() {
    UUID id = account(SystemRole.SYSTEM_ADMIN);
    User detached = users.findById(id).orElseThrow();
    ForeignCommit.commit(
        dataSource,
        "UPDATE users SET system_role = 'USER', directory_locked_at = now(),"
            + " version = version + 1 WHERE id = ?",
        id);

    detached.setDisplayName("Neu");
    assertThatThrownBy(() -> users.save(detached))
        .isInstanceOf(OptimisticLockingFailureException.class);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT system_role, directory_locked_at, display_name FROM users WHERE id = ?", id);
    assertThat(row.get("system_role")).isEqualTo("USER");
    assertThat(row.get("directory_locked_at")).isNotNull();
    assertThat(row.get("display_name")).isEqualTo("Alt");
  }

  @Test
  void aDetachedProviderSavedAfterItWasSwitchedOffFailsInsteadOfSwitchingItBackOn() {
    providerId = providers.saveAndFlush(provider()).getId();
    OidcProvider detached = providers.findById(providerId).orElseThrow();
    ForeignCommit.commit(
        dataSource,
        "UPDATE oidc_providers SET enabled = false, version = version + 1 WHERE id = ?",
        providerId);

    detached.rename("Zeilenprobe neu");
    assertThatThrownBy(() -> providers.save(detached))
        .isInstanceOf(OptimisticLockingFailureException.class);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT enabled, display_name FROM oidc_providers WHERE id = ?", providerId);
    assertThat(row.get("enabled")).isEqualTo(false);
    assertThat(row.get("display_name")).isEqualTo("Zeilenprobe");
  }

  private UUID account(SystemRole role) {
    User created =
        new User(UUID.randomUUID().toString(), "row-write-issuer", "zeile@stadt.example", "Alt");
    created.setOrganizationId(Organization.DEFAULT_ID);
    created.setSystemRole(role);
    return users.saveAndFlush(created).getId();
  }

  private static OidcProvider provider() {
    return new OidcProvider(
        "Zeilenprobe",
        "https://row-write-" + UUID.randomUUID() + ".example/realms/opaa",
        "opaa",
        null,
        OidcClaimMapping.keycloakDefaults());
  }

  @Test
  void aProviderEditDoesNotSwitchBackOnAProviderSwitchedOffAfterItsRead() {
    OidcProvider created =
        new OidcProvider(
            "Zeilenprobe",
            "https://row-write-" + UUID.randomUUID() + ".example/realms/opaa",
            "opaa",
            null,
            OidcClaimMapping.keycloakDefaults());
    providerId = providers.saveAndFlush(created).getId();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              OidcProvider read = providers.findById(providerId).orElseThrow();
              ForeignCommit.commit(
                  dataSource, "UPDATE oidc_providers SET enabled = false WHERE id = ?", providerId);
              read.rename("Zeilenprobe neu");
              providers.save(read);
            });

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT enabled, display_name FROM oidc_providers WHERE id = ?", providerId);
    assertThat(row.get("enabled")).isEqualTo(false);
    assertThat(row.get("display_name")).isEqualTo("Zeilenprobe neu");
  }
}
