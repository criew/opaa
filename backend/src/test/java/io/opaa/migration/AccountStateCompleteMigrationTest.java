package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code rights/2026-10-21-account-state-complete.yaml} on an existing installation: every account
 * that is unusable at the introduction - a local account locked, expired, invited or behind the
 * switched-off local management, an account of a switched-off or deleted provider - gets an open
 * {@code LOCKED} interval from the introduction on, and nothing before it, because the history
 * cannot tell since when. The introduction is recorded as the start of the complete account-state
 * history. A usable account, and one of an issuer no provider ever had, gets no row.
 */
class AccountStateCompleteMigrationTest extends AbstractBaselineTest {

  private static final String FILE = "db/changelog/rights/2026-10-21-account-state-complete.yaml";
  private static final String LOCAL = "urn:opaa:local";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void everyAccountUnusableAtTheIntroductionIsLockedFromItOn() throws Exception {
    localProvider(true);
    UUID locked = localAccount("password_hash = 'x', locked_at = now(), locked_reason = 'ADMIN'");
    UUID inactive =
        localAccount("password_hash = 'x', locked_at = now(), locked_reason = 'INACTIVITY'");
    UUID expired = localAccount("password_hash = 'x', expires_at = now() - interval '1 day'");
    UUID invited = localAccount("password_hash = NULL");
    UUID lockedOut =
        localAccount(
            "password_hash = 'x', locked_at = now(), locked_reason = 'FAILED_LOGINS',"
                + " lockout_until = now() + interval '10 minutes'");
    UUID usable = localAccount("password_hash = 'x'");
    UUID lockoutOver =
        localAccount(
            "password_hash = 'x', locked_at = now() - interval '1 hour',"
                + " locked_reason = 'FAILED_LOGINS', lockout_until = now() - interval '45 minutes'");
    String disabledIssuer = "https://idp.example/realms/" + UUID.randomUUID();
    UUID disabledProvider = insertProvider(disabledIssuer, false);
    execute("UPDATE oidc_providers SET enabled = false WHERE id = '" + disabledProvider + "'");
    UUID ofDisabled = user(disabledIssuer);
    String removedIssuer = "https://idp.example/realms/" + UUID.randomUUID();
    execute(
        "INSERT INTO oidc_provider_removals (issuer_uri_normalized, removed_at) VALUES ('"
            + removedIssuer
            + "', now())");
    UUID ofRemoved = user(removedIssuer + "/");
    String enabledIssuer = "https://idp.example/realms/" + UUID.randomUUID();
    insertProvider(enabledIssuer, false);
    UUID ofEnabled = user(enabledIssuer);
    UUID ofUnknownIssuer = user("https://dev.example/never-a-provider");
    UUID directoryLocked = user(enabledIssuer);
    execute("UPDATE users SET directory_locked_at = now() WHERE id = '" + directoryLocked + "'");
    Instant before = databaseNow();

    applyChangelog(connection, FILE);

    Instant completeSince =
        instantOf("SELECT account_state_complete_since FROM permission_history_retention_settings");
    assertThat(completeSince).isAfterOrEqualTo(before);
    assertThat(intervalsOf(locked)).containsExactly("LOCKED ADMIN_LOCKED open");
    assertThat(intervalsOf(inactive)).containsExactly("LOCKED INACTIVITY_LOCKED open");
    assertThat(intervalsOf(expired)).containsExactly("LOCKED LOCAL_EXPIRED open");
    assertThat(intervalsOf(invited)).containsExactly("LOCKED LOCAL_INVITED open");
    assertThat(intervalsOf(lockedOut)).containsExactly("LOCKED LOGIN_LOCKOUT open");
    assertThat(intervalsOf(ofDisabled)).containsExactly("LOCKED PROVIDER_DISABLED open");
    assertThat(intervalsOf(ofRemoved)).containsExactly("LOCKED PROVIDER_REMOVED open");
    assertThat(intervalsOf(directoryLocked)).containsExactly("LOCKED DIRECTORY_LOCKED open");
    assertThat(intervalsOf(usable)).isEmpty();
    assertThat(intervalsOf(lockoutOver)).isEmpty();
    assertThat(intervalsOf(ofEnabled)).isEmpty();
    assertThat(intervalsOf(ofUnknownIssuer)).isEmpty();
    assertThat(
            longOf(
                "SELECT count(*) FROM account_state_history WHERE valid_from < '"
                    + completeSince
                    + "'"))
        .as("nothing is claimed for the time before the introduction")
        .isZero();
    assertThat(
            instantOf(
                "SELECT locked_until FROM account_state_history WHERE user_id = '"
                    + lockedOut
                    + "'"))
        .isAfter(completeSince);
  }

  @Test
  void theRegularLocalAccountsBehindTheSwitchedOffManagementAreLockedButNotItsAdministrators()
      throws Exception {
    localProvider(false);
    UUID regular = localAccount("password_hash = 'x'");
    UUID administrator = localAccount("password_hash = 'x'");
    execute("UPDATE users SET system_role = 'SYSTEM_ADMIN' WHERE id = '" + administrator + "'");

    applyChangelog(connection, FILE);

    assertThat(intervalsOf(regular)).containsExactly("LOCKED LOCAL_ACCOUNTS_DISABLED open");
    assertThat(intervalsOf(administrator)).isEmpty();
  }

  /** A chain the directory synchronisation began is continued, not started over. */
  @Test
  void anExistingChainIsContinuedFromItsOpenInterval() throws Exception {
    String issuer = "https://idp.example/realms/" + UUID.randomUUID();
    UUID provider = insertProvider(issuer, false);
    execute("UPDATE oidc_providers SET enabled = false WHERE id = '" + provider + "'");
    UUID account = user(issuer);
    execute(
        "INSERT INTO account_state_history (id, user_id, organization_id, state, cause,"
            + " valid_from, valid_to, created_at) VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + account
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'ACTIVE', 'DIRECTORY_UNLOCKED', now() - interval '1 day', NULL, now())");

    applyChangelog(connection, FILE);

    assertThat(intervalsOf(account))
        .containsExactly("ACTIVE DIRECTORY_UNLOCKED closed", "LOCKED PROVIDER_DISABLED open");
    assertThat(
            longOf(
                "SELECT count(*) FROM account_state_history a JOIN account_state_history b"
                    + " ON a.user_id = b.user_id AND a.valid_to = b.valid_from WHERE a.user_id = '"
                    + account
                    + "'"))
        .as("closing and opening share one boundary")
        .isEqualTo(1L);
  }

  /**
   * The intervals of an account alone name no access - every period they could cut stands in
   * another history whose person column stays {@code RESTRICT} - so they go with the account.
   */
  @Test
  void theIntervalsOfADeletedAccountGoWithIt() throws Exception {
    localProvider(true);
    UUID account = localAccount("password_hash = 'x', locked_at = now(), locked_reason = 'ADMIN'");
    applyChangelog(connection, FILE);
    assertThat(intervalsOf(account)).hasSize(1);

    execute("DELETE FROM local_credentials WHERE user_id = '" + account + "'");
    execute("DELETE FROM users WHERE id = '" + account + "'");

    assertThat(intervalsOf(account)).isEmpty();
  }

  // ---- helpers

  private void localProvider(boolean enabled) throws SQLException {
    execute(
        "INSERT INTO oidc_providers (id, display_name, enabled, issuer_uri, provider_type,"
            + " is_external) VALUES ('"
            + UUID.randomUUID()
            + "', 'Lokale Konten', "
            + enabled
            + ", '"
            + LOCAL
            + "', 'LOCAL', false)");
  }

  private UUID user(String issuer) throws SQLException {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, system_role, organization_id) VALUES ('"
            + id
            + "', '"
            + id
            + "', '"
            + issuer
            + "', 'USER', '"
            + SEEDED_ORGANIZATION_ID
            + "')");
    return id;
  }

  /** A local account whose credentials row carries {@code assignments} on a verified address. */
  private UUID localAccount(String assignments) throws SQLException {
    UUID id = user(LOCAL);
    execute(
        "INSERT INTO local_credentials (user_id, created_reason, email_verified_at) VALUES ('"
            + id
            + "', 'Test', now())");
    execute("UPDATE local_credentials SET " + assignments + " WHERE user_id = '" + id + "'");
    return id;
  }

  private List<String> intervalsOf(UUID userId) throws SQLException {
    List<String> intervals = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT state, cause, valid_to IS NULL AS open FROM account_state_history"
                    + " WHERE user_id = '"
                    + userId
                    + "' ORDER BY valid_from")) {
      while (rs.next()) {
        intervals.add(
            rs.getString("state")
                + " "
                + rs.getString("cause")
                + " "
                + (rs.getBoolean("open") ? "open" : "closed"));
      }
    }
    return intervals;
  }

  private Instant instantOf(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      assertThat(rs.next()).as("a row for: %s", sql).isTrue();
      return rs.getObject(1, OffsetDateTime.class).toInstant();
    }
  }

  private Instant databaseNow() throws SQLException {
    return instantOf("SELECT clock_timestamp()");
  }
}
