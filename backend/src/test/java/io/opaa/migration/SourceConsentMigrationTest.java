package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * "Quelle verbinden" (#2169) applied to an existing installation: existing secrets, connections and
 * consents stay as they are, a secret still has exactly one owner - now also a person's pending
 * consent, only for a while -, and a library's connection names its responsible and why it ended
 * only in the forms the code writes; the consenting person may leave.
 */
class SourceConsentMigrationTest extends AbstractBaselineTest {

  private static final String FILE = "db/changelog/connections/2026-10-05-source-consent.yaml";

  private UUID person;
  private UUID profile;
  private UUID library;
  private UUID account;

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @BeforeEach
  void anInstallationWithASecretAConnectionAndAConsentInProgress() throws Exception {
    person = insertUser();
    profile = UUID.randomUUID();
    execute(
        "INSERT INTO connection_profiles (id, name, source_type, server_url, auth_method,"
            + " ownership, source_insecure_ssl, created_at, updated_at, version) VALUES ('"
            + profile
            + "', 'Zugang "
            + profile
            + "', 'CONSENT_PROBE', 'https://consent.example.org', 'OAUTH', 'BOTH', false, now(),"
            + " now(), 0)");
    library = insertLibrary("CONSENT_PROBE", "https://consent.example.org/bauamt");
    execute(
        "INSERT INTO library_connections (library_id, profile_id, created_at, updated_at, version)"
            + " VALUES ('"
            + library
            + "', '"
            + profile
            + "', now(), now(), 0)");
    account = UUID.randomUUID();
    execute(
        "INSERT INTO connected_accounts (id, organization_id, user_id, profile_id, state,"
            + " connected_at, version) VALUES ('"
            + account
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', '"
            + person
            + "', '"
            + profile
            + "', 'CONNECTED', now(), 0)");
    execute(
        "INSERT INTO connection_tokens (id, profile_id, connected_account_id, kind,"
            + " secret_ciphertext, issued_for, created_at, updated_at, version) VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + profile
            + "', '"
            + account
            + "', 'OAUTH', 'enc:x', 'https://consent.example.org', now(), now(), 0)");
    execute(
        "INSERT INTO connection_authorizations (id, state_hash, user_id, profile_id,"
            + " profile_version, purpose, code_verifier_ciphertext, redirect_uri, created_at,"
            + " expires_at) VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + "a".repeat(64)
            + "', '"
            + person
            + "', '"
            + profile
            + "', 0, 'ACCOUNT', 'enc:x', 'https://opaa.example.org/connections/callback', now(),"
            + " now() + interval '10 minutes')");

    applyChangelog(connection, FILE);
  }

  @Test
  void existingRowsStayAndCarryNoneOfTheNewFacts() throws Exception {
    assertThat(
            countWhere(
                "connection_tokens",
                "connected_account_id = '"
                    + account
                    + "' AND pending_user_id IS NULL AND pending_expires_at IS NULL"))
        .isEqualTo(1);
    assertThat(
            countWhere(
                "library_connections",
                "library_id = '"
                    + library
                    + "' AND connected_at IS NULL AND responsible_type IS NULL"
                    + " AND ended_cause IS NULL"))
        .isEqualTo(1);
    assertThat(
            countWhere(
                "connection_authorizations",
                "profile_id = '" + profile + "' AND service_account_confirmed_at IS NULL"))
        .isEqualTo(1);
  }

  @Test
  void aSecretHasExactlyOneOwnerAndAPendingConsentOnlyForAWhile() throws Exception {
    assertRejected(token(null, null, null), "chk_connection_tokens_owner");
    assertRejected(token(account, library, null), "chk_connection_tokens_owner");
    assertRejected(token(null, library, person), "chk_connection_tokens_owner");
    assertRejected(
        "INSERT INTO connection_tokens (id, profile_id, pending_user_id, kind, secret_ciphertext,"
            + " issued_for, created_at, updated_at, version) VALUES ('"
            + UUID.randomUUID()
            + "', '"
            + profile
            + "', '"
            + person
            + "', 'OAUTH', 'enc:x', 'https://consent.example.org', now(), now(), 0)",
        "chk_connection_tokens_pending");

    execute(token(null, library, null));
    UUID pending = UUID.randomUUID();
    execute(token(pending, null, null, person));

    execute("DELETE FROM connected_accounts WHERE id = '" + account + "'");
    execute("DELETE FROM users WHERE id = '" + person + "'");
    assertThat(countWhere("connection_tokens", "id = '" + pending + "'"))
        .as("a pending consent goes with its person")
        .isZero();
    assertThat(countWhere("connection_tokens", "library_id = '" + library + "'")).isEqualTo(1);
  }

  @Test
  void aConnectionNamesItsResponsibleAndItsEndOnlyAsTheCodeWritesThem() throws Exception {
    assertRejected(update("responsible_type = 'USER'"), "chk_library_connections_responsible");
    assertRejected(
        update("responsible_type = 'ROLE', responsible_id = '" + person + "'"),
        "chk_library_connections_responsible");
    assertRejected(update("ended_cause = 'SELF'"), "chk_library_connections_ended");
    assertRejected(
        update("ended_cause = 'LIBRARY_DELETED', ended_at = now()"),
        "chk_library_connections_ended");

    execute(
        update(
            "account_label = 'bauamt@example.org', connected_by = '"
                + person
                + "', connected_at = now(), responsible_type = 'GROUP', responsible_id = '"
                + UUID.randomUUID()
                + "', ended_cause = 'PROVIDER_REJECTED', ended_at = now()"));
    execute("DELETE FROM connected_accounts WHERE id = '" + account + "'");
    execute("DELETE FROM users WHERE id = '" + person + "'");

    assertThat(
            countWhere(
                "library_connections",
                "library_id = '"
                    + library
                    + "' AND connected_by IS NULL AND account_label = 'bauamt@example.org'"))
        .as("the connection stays the library's when the consenting person leaves")
        .isEqualTo(1);
  }

  private String update(String assignments) {
    return "UPDATE library_connections SET "
        + assignments
        + " WHERE library_id = '"
        + library
        + "'";
  }

  private String token(UUID accountId, UUID libraryId, UUID pendingUser) {
    return token(UUID.randomUUID(), accountId, libraryId, pendingUser);
  }

  private String token(UUID id, UUID accountId, UUID libraryId, UUID pendingUser) {
    return "INSERT INTO connection_tokens (id, profile_id, connected_account_id, library_id,"
        + " pending_user_id, pending_expires_at, kind, secret_ciphertext, issued_for, created_at,"
        + " updated_at, version) VALUES ('"
        + id
        + "', '"
        + profile
        + "', "
        + quoted(accountId)
        + ", "
        + quoted(libraryId)
        + ", "
        + quoted(pendingUser)
        + ", "
        + (pendingUser == null ? "NULL" : "now() + interval '1 hour'")
        + ", 'OAUTH', 'enc:x', 'https://consent.example.org', now(), now(), 0)";
  }
}
