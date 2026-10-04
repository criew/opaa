package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Connection profile requests applied to an existing installation: a request goes with the account
 * of its requester, keeps its resolution when the resolving account or the serving profile goes,
 * and one person holds at most one open request per type and address.
 */
class ConnectionProfileRequestMigrationTest extends AbstractBaselineTest {

  private static final String FILE = "db/changelog/connections/2026-10-04-profile-requests.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void aRequestGoesWithItsRequesterAndOutlivesItsResolverAndItsProfile() throws Exception {
    UUID profile = UUID.randomUUID();
    execute(
        "INSERT INTO connection_profiles (id, name, source_type, server_url, auth_method,"
            + " ownership, created_at, updated_at, version) VALUES ("
            + quoted(profile)
            + ", 'Zugang Bestand', 'PROFILE_PROBE', 'https://probe.example.org', 'NONE',"
            + " 'LIBRARY', now(), now(), 0)");
    applyChangelog(connection, FILE);

    UUID requester = insertUser();
    UUID resolver = insertUser();
    UUID open = insert(requester, "https://a.example.org", "'OPEN'", "NULL", "NULL", "NULL");
    UUID done =
        insert(
            requester,
            "https://b.example.org",
            "'DONE'",
            quoted(resolver),
            "now()",
            quoted(profile));

    execute("DELETE FROM connection_profiles WHERE id = " + quoted(profile));
    execute("DELETE FROM users WHERE id = " + quoted(resolver));
    assertThat(
            countWhere(
                "connection_profile_requests",
                "id = "
                    + quoted(done)
                    + " AND state = 'DONE' AND profile_id IS NULL AND resolved_by IS NULL"
                    + " AND resolved_at IS NOT NULL"))
        .isEqualTo(1);

    execute("DELETE FROM users WHERE id = " + quoted(requester));
    assertThat(
            countWhere(
                "connection_profile_requests",
                "id IN (" + quoted(open) + ", " + quoted(done) + ")"))
        .isZero();
  }

  @Test
  void onePersonHoldsOneOpenRequestPerTypeAndAddressAndAResolutionIsConsistent() throws Exception {
    applyChangelog(connection, FILE);
    UUID requester = insertUser();

    insert(requester, "https://c.example.org", "'OPEN'", "NULL", "NULL", "NULL");
    assertRejected(
        insertSql(requester, "https://c.example.org", "'OPEN'", "NULL", "NULL", "NULL"),
        "ux_connection_profile_requests_open");
    insert(requester, "https://c.example.org", "'DECLINED'", "NULL", "now()", "NULL");
    assertRejected(
        insertSql(requester, "https://d.example.org", "'DONE'", "NULL", "NULL", "NULL"),
        "chk_connection_profile_requests_resolution");
    assertRejected(
        insertSql(requester, "https://e.example.org", "'PENDING'", "NULL", "now()", "NULL"),
        "chk_connection_profile_requests_state");
  }

  private UUID insert(
      UUID requester,
      String url,
      String state,
      String resolvedBy,
      String resolvedAt,
      String profile)
      throws Exception {
    UUID id = UUID.randomUUID();
    execute(insertSql(id, requester, url, state, resolvedBy, resolvedAt, profile));
    return id;
  }

  private String insertSql(
      UUID requester,
      String url,
      String state,
      String resolvedBy,
      String resolvedAt,
      String profile) {
    return insertSql(UUID.randomUUID(), requester, url, state, resolvedBy, resolvedAt, profile);
  }

  private static String insertSql(
      UUID id,
      UUID requester,
      String url,
      String state,
      String resolvedBy,
      String resolvedAt,
      String profile) {
    return "INSERT INTO connection_profile_requests (id, organization_id, source_type, server_url,"
        + " requested_by, created_at, state, resolved_by, resolved_at, profile_id, version)"
        + " VALUES ("
        + quoted(id)
        + ", (SELECT organization_id FROM users WHERE id = "
        + quoted(requester)
        + "), 'PROFILE_PROBE', '"
        + url
        + "', "
        + quoted(requester)
        + ", now(), "
        + state
        + ", "
        + resolvedBy
        + ", "
        + resolvedAt
        + ", "
        + profile
        + ", 0)";
  }
}
