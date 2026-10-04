package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Connected accounts and the token store, applied to an existing installation: what the database
 * itself guarantees regardless of any code path - one owner per secret, a person's secret on its
 * account's profile, a private library only on a profile for persons its owner has connected.
 */
class TokenStoreMigrationTest extends AbstractBaselineTest {

  private static final String FILE = "db/changelog/connections/2026-10-04-token-store.yaml";

  /** Alter tables of {@link #FILE}, so an installation without that file lacks them too. */
  private static final String USAGE =
      "db/changelog/connections/2026-10-04-usage-and-person-states.yaml";

  private static final String WARNED_EXPIRY =
      "db/changelog/connections/2026-10-04-warned-expiry.yaml";

  private UUID owner;
  private UUID forPersons;
  private UUID forLibraries;
  private UUID privateLibrary;
  private UUID sharedLibrary;

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE, USAGE, WARNED_EXPIRY);
  }

  @BeforeEach
  void anInstallationWithProfilesAndLibraries() throws Exception {
    owner = insertUser();
    forPersons = insertProfile("PERSON");
    forLibraries = insertProfile("LIBRARY");
    sharedLibrary = insertLibrary("PERSON_PROBE", "https://person.example.org/a");
    privateLibrary = UUID.randomUUID();
    execute(
        "INSERT INTO assets (id, asset_type, organization_id, name, owner_type, owner_user_id,"
            + " owner_only) VALUES ('"
            + privateLibrary
            + "', 'KNOWLEDGE_LIBRARY', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'Privat', 'USER', '"
            + owner
            + "', true)");
    execute(
        "INSERT INTO knowledge_libraries (id, organization_id, source_type) VALUES ('"
            + privateLibrary
            + "', '"
            + SEEDED_ORGANIZATION_ID
            + "', 'PERSON_PROBE')");
    // an existing connection of a shared library stays untouched
    execute(connect(sharedLibrary, forLibraries));

    applyChangelog(connection, FILE);
  }

  @Test
  void anExistingConnectionStaysAndASharedLibraryGoesOntoAnyProfile() throws Exception {
    assertThat(countWhere("library_connections", "library_id = '" + sharedLibrary + "'"))
        .isEqualTo(1);
    execute(
        "UPDATE library_connections SET profile_id = '"
            + forPersons
            + "' WHERE library_id = '"
            + sharedLibrary
            + "'");
  }

  @Test
  void aPrivateLibraryGoesOnlyOntoAProfileForPersonsItsOwnerHasConnected() throws Exception {
    assertRejected(connect(privateLibrary, forLibraries), "only onto a profile for persons");
    assertRejected(connect(privateLibrary, forPersons), "only onto a profile its owner has");

    insertAccount(owner, forPersons, "CONNECTED", null);
    execute(connect(privateLibrary, forPersons));

    assertRejected(
        "UPDATE library_connections SET profile_id = '"
            + forLibraries
            + "' WHERE library_id = '"
            + privateLibrary
            + "'",
        "only onto a profile for persons");
    // the profile's deletion leaves the connection without a profile ("Zugang entfernt")
    execute("DELETE FROM connection_profiles WHERE id = '" + forPersons + "'");
    assertThat(
            countWhere(
                "library_connections",
                "library_id = '" + privateLibrary + "' AND profile_id IS NULL"))
        .isEqualTo(1);
  }

  @Test
  void aSecretHasExactlyOneOwnerOnItsOwnersProfile() throws Exception {
    UUID account = insertAccount(owner, forPersons, "CONNECTED", null);

    assertRejected(token(forPersons, null, null, "PERSONAL_SECRET"), "chk_connection_tokens_owner");
    assertRejected(
        token(forPersons, account, sharedLibrary, "PERSONAL_SECRET"),
        "chk_connection_tokens_owner");
    assertRejected(token(forLibraries, account, null, "PERSONAL_SECRET"), "fk_connection_tokens");
    assertRejected(token(forPersons, account, null, "SAML"), "chk_connection_tokens_kind");

    execute(token(forPersons, account, null, "PERSONAL_SECRET"));
    assertRejected(token(forPersons, account, null, "PERSONAL_SECRET"), "ux_connection_tokens");
    execute(token(forLibraries, null, sharedLibrary, "PERSONAL_SECRET"));

    execute("DELETE FROM connected_accounts WHERE id = '" + account + "'");
    assertThat(countWhere("connection_tokens", "connected_account_id IS NOT NULL")).isZero();
  }

  @Test
  void anAccountIsOnePerPersonAndProfileAndNamesWhyItEnded() throws Exception {
    insertAccount(owner, forPersons, "CONNECTED", null);

    assertRejected(insertAccountSql(owner, forPersons, "CONNECTED", null), "ux_connected_accounts");
    assertRejected(
        insertAccountSql(owner, forLibraries, "EXPIRED", null),
        "chk_connected_accounts_cause_of_end");
    assertRejected(
        insertAccountSql(owner, forLibraries, "CONNECTED", "SELF"),
        "chk_connected_accounts_cause_of_end");
    assertRejected(
        insertAccountSql(owner, forLibraries, "DORMANT", "SELF"), "chk_connected_accounts_state");
    insertAccount(owner, forLibraries, "EXPIRED", "PROVIDER_REJECTED");
  }

  @Test
  void aPersonWithAConnectionIsNotDeleted() throws Exception {
    UUID person = insertUser();
    insertAccount(person, forPersons, "DISCONNECTED", "SELF");

    assertRejected(
        "DELETE FROM users WHERE id = '" + person + "'", "fk_connected_accounts_user_organization");
  }

  @Test
  void theProfilesDeletionTakesItsAccountsAndSecrets() throws Exception {
    UUID account = insertAccount(owner, forPersons, "CONNECTED", null);
    execute(token(forPersons, account, null, "PERSONAL_SECRET"));

    execute("DELETE FROM connection_profiles WHERE id = '" + forPersons + "'");

    assertThat(countWhere("connected_accounts", "id = '" + account + "'")).isZero();
    assertThat(countWhere("connection_tokens", "profile_id = '" + forPersons + "'")).isZero();
  }

  private UUID insertProfile(String ownership) throws Exception {
    UUID id = UUID.randomUUID();
    execute(
        "INSERT INTO connection_profiles (id, name, source_type, server_url, auth_method,"
            + " ownership, source_insecure_ssl, created_at, updated_at, version) VALUES ('"
            + id
            + "', 'Zugang "
            + id
            + "', 'PERSON_PROBE', 'https://person.example.org', 'PERSONAL_SECRET', '"
            + ownership
            + "', false, now(), now(), 0)");
    return id;
  }

  private UUID insertAccount(UUID user, UUID profile, String state, String cause) throws Exception {
    UUID id = UUID.randomUUID();
    execute(insertAccountSql(id, user, profile, state, cause));
    return id;
  }

  private static String insertAccountSql(UUID user, UUID profile, String state, String cause) {
    return insertAccountSql(UUID.randomUUID(), user, profile, state, cause);
  }

  private static String insertAccountSql(
      UUID id, UUID user, UUID profile, String state, String cause) {
    return "INSERT INTO connected_accounts (id, organization_id, user_id, profile_id, state,"
        + " ended_cause, connected_at, version) VALUES ('"
        + id
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + user
        + "', '"
        + profile
        + "', '"
        + state
        + "', "
        + quoted(cause)
        + ", now(), 0)";
  }

  private static String token(UUID profile, UUID account, UUID library, String kind) {
    return "INSERT INTO connection_tokens (id, profile_id, connected_account_id, library_id, kind,"
        + " secret_ciphertext, issued_for, created_at, updated_at, version) VALUES"
        + " (gen_random_uuid(), '"
        + profile
        + "', "
        + quoted(account)
        + ", "
        + quoted(library)
        + ", '"
        + kind
        + "', 'enc:v1:x', 'https://person.example.org', now(), now(), 0)";
  }

  private static String connect(UUID library, UUID profile) {
    return "INSERT INTO library_connections (library_id, profile_id, created_at, updated_at,"
        + " version) VALUES ('"
        + library
        + "', '"
        + profile
        + "', now(), now(), 0)";
  }
}
