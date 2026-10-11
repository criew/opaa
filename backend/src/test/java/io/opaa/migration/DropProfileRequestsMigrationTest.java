package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The removal of connection profile requests: the table {@code connection_profile_requests} goes
 * with its rows, while the requesting account and the profile a request named stay. Applied to an
 * existing installation, i.e. the master without this file.
 */
class DropProfileRequestsMigrationTest extends AbstractBaselineTest {

  private static final String FILE =
      "db/changelog/connections/2026-10-11-drop-profile-requests.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void theTableIsGoneAndTheAccountAndTheProfileStay() throws Exception {
    UUID profile = UUID.randomUUID();
    execute(
        "INSERT INTO connection_profiles (id, name, source_type, server_url, auth_method,"
            + " ownership, created_at, updated_at, version) VALUES ("
            + quoted(profile)
            + ", 'Zugang Bestand', 'PROFILE_PROBE', 'https://probe.example.org', 'NONE',"
            + " 'LIBRARY', now(), now(), 0)");
    UUID requester = insertUser();
    execute(
        "INSERT INTO connection_profile_requests (id, organization_id, source_type, server_url,"
            + " requested_by, created_at, state, resolved_by, resolved_at, profile_id, version)"
            + " VALUES (gen_random_uuid(), (SELECT organization_id FROM users WHERE id = "
            + quoted(requester)
            + "), 'PROFILE_PROBE', 'https://probe.example.org', "
            + quoted(requester)
            + ", now(), 'DONE', NULL, now(), "
            + quoted(profile)
            + ", 0)");

    applyChangelog(connection, FILE);

    assertThat(
            countWhere(
                "information_schema.tables",
                "table_schema = current_schema() AND table_name = 'connection_profile_requests'"))
        .isZero();
    assertThat(countWhere("connection_profiles", "id = " + quoted(profile))).isEqualTo(1);
    assertThat(countWhere("users", "id = " + quoted(requester))).isEqualTo(1);
  }
}
