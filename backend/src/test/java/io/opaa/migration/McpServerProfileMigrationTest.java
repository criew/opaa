package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The profile kind applied to an existing installation: every profile becomes a connector profile
 * with its type kept, a connector profile still needs a type and an MCP server profile has none,
 * and deleting the responsible group leaves the profile without one.
 */
class McpServerProfileMigrationTest extends AbstractBaselineTest {

  private static final String FILE = "db/changelog/connections/2026-10-05-mcp-server-profiles.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void anExistingProfileBecomesAConnectorProfileWithItsType() throws Exception {
    UUID profile = insertConnectorProfile("Zugang Bestand");

    applyChangelog(connection, FILE);

    assertThat(stringOf("SELECT kind FROM connection_profiles WHERE id = " + quoted(profile)))
        .isEqualTo("CONNECTOR");
    assertThat(
            stringOf("SELECT source_type FROM connection_profiles WHERE id = " + quoted(profile)))
        .isEqualTo("NEXTCLOUD");
    assertThat(
            countWhere(
                "connection_profiles",
                "id = " + quoted(profile) + " AND responsible_group_id IS NULL AND issuer IS NULL"))
        .isOne();
  }

  @Test
  void aConnectorProfileNeedsATypeAndAnMcpServerProfileHasNone() throws Exception {
    applyChangelog(connection, FILE);

    assertRejected(
        profileInsert("'CONNECTOR'", "NULL", "Zugang ohne Quellart"),
        "chk_connection_profiles_kind_source_type");
    assertRejected(
        profileInsert("'MCP_SERVER'", "'NEXTCLOUD'", "MCP mit Quellart"),
        "chk_connection_profiles_kind_source_type");
    assertRejected(profileInsert("'PLUGIN'", "NULL", "Fremde Art"), "chk_connection_profiles_kind");

    execute(profileInsert("'MCP_SERVER'", "NULL", "MCP-Server Wiki"));

    assertThat(countWhere("connection_profiles", "kind = 'MCP_SERVER' AND source_type IS NULL"))
        .isOne();
  }

  @Test
  void deletingTheResponsibleGroupLeavesTheProfileWithoutOne() throws Exception {
    applyChangelog(connection, FILE);
    UUID group = insertInternalGroup();
    UUID profile = UUID.randomUUID();
    execute(
        "INSERT INTO connection_profiles (id, name, kind, server_url, auth_method, ownership,"
            + " client_id, responsible_group_id, issuer, created_at, updated_at, version) VALUES ("
            + quoted(profile)
            + ", 'MCP-Server Tickets', 'MCP_SERVER', 'https://mcp.example.org/mcp', 'OAUTH',"
            + " 'PERSON', 'opaa', "
            + quoted(group)
            + ", 'https://auth.example.org', now(), now(), 0)");

    execute("DELETE FROM groups WHERE id = " + quoted(group));

    assertThat(
            countWhere(
                "connection_profiles",
                "id = " + quoted(profile) + " AND responsible_group_id IS NULL"))
        .isOne();
  }

  private UUID insertConnectorProfile(String name) throws Exception {
    UUID profile = UUID.randomUUID();
    execute(
        "INSERT INTO connection_profiles (id, name, source_type, server_url, auth_method,"
            + " ownership, created_at, updated_at, version) VALUES ("
            + quoted(profile)
            + ", '"
            + name
            + "', 'NEXTCLOUD', 'https://cloud.example.org', 'PERSONAL_SECRET', 'PERSON', now(),"
            + " now(), 0)");
    return profile;
  }

  private static String profileInsert(String kind, String sourceType, String name) {
    return "INSERT INTO connection_profiles (id, name, kind, source_type, server_url, auth_method,"
        + " ownership, created_at, updated_at, version) VALUES ("
        + quoted(UUID.randomUUID())
        + ", '"
        + name
        + "', "
        + kind
        + ", "
        + sourceType
        + ", 'https://mcp.example.org/mcp', 'OAUTH', 'PERSON', now(), now(), 0)";
  }
}
