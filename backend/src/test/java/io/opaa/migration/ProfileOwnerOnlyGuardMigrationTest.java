package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The condition of the trigger on {@code library_connections} after a change of the profile: a
 * profile carrying a private library keeps admitting persons until the library is released from it,
 * checked at commit so a change may release first and narrow then.
 */
class ProfileOwnerOnlyGuardMigrationTest extends AbstractBaselineTest {

  private static final String FILE =
      "db/changelog/connections/2026-10-04-profile-owner-only-guard.yaml";

  private UUID profile;
  private UUID privateLibrary;

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @BeforeEach
  void aPrivateLibraryOnAProfileForPersons() throws Exception {
    UUID owner = insertUser();
    profile = UUID.randomUUID();
    execute(
        "INSERT INTO connection_profiles (id, name, source_type, server_url, auth_method,"
            + " ownership, source_insecure_ssl, created_at, updated_at, version) VALUES ('"
            + profile
            + "', 'Zugang Personen', 'PERSON_PROBE', 'https://person.example.org',"
            + " 'PERSONAL_SECRET', 'BOTH', false, now(), now(), 0)");
    execute(
        "INSERT INTO connected_accounts (id, organization_id, user_id, profile_id, state,"
            + " connected_at, version) VALUES (gen_random_uuid(), '"
            + SEEDED_ORGANIZATION_ID
            + "', '"
            + owner
            + "', '"
            + profile
            + "', 'CONNECTED', now(), 0)");
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
    execute(
        "INSERT INTO library_connections (library_id, profile_id, created_at, updated_at,"
            + " version) VALUES ('"
            + privateLibrary
            + "', '"
            + profile
            + "', now(), now(), 0)");

    applyChangelog(connection, FILE);
  }

  @Test
  void aProfileCarryingAPrivateLibraryKeepsAdmittingPersons() throws Exception {
    assertRejected(ownership("LIBRARY"), "must admit persons");
    execute(ownership("PERSON"));

    assertThat(stringOf("SELECT ownership FROM connection_profiles WHERE id = '" + profile + "'"))
        .isEqualTo("PERSON");
  }

  @Test
  void releasingThePrivateLibraryFirstLetsTheProfileNarrowInTheSameTransaction() throws Exception {
    connection.setAutoCommit(false);
    try {
      execute(
          "UPDATE library_connections SET profile_id = NULL WHERE library_id = '"
              + privateLibrary
              + "'");
      execute(ownership("LIBRARY"));
      connection.commit();
    } finally {
      connection.setAutoCommit(true);
    }

    assertThat(stringOf("SELECT ownership FROM connection_profiles WHERE id = '" + profile + "'"))
        .isEqualTo("LIBRARY");
  }

  private String ownership(String ownership) {
    return "UPDATE connection_profiles SET ownership = '"
        + ownership
        + "' WHERE id = '"
        + profile
        + "'";
  }
}
