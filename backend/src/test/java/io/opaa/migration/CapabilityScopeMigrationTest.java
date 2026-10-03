package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The scope of {@code CREATE_CONNECTOR_LIBRARY} (#2161, ADR-0036, Nachtrag of 03.10.2026): applied
 * to an existing installation, every grant and interval of it becomes one per delivered connector
 * type, the uniqueness covers the scope, and a later organization is seeded per type.
 */
class CapabilityScopeMigrationTest extends AbstractBaselineTest {

  private static final String FILE = "db/changelog/rights/2026-10-03-capability-scope.yaml";
  private static final List<String> TYPES =
      List.of(
          "TYPE:CONFLUENCE", "TYPE:FILESYSTEM", "TYPE:HTTP_DIRECTORY", "TYPE:RSS_FEED", "TYPE:S3");

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void everyConnectorGrantAndIntervalBecomesOnePerDeliveredType() throws Exception {
    UUID group = insertInternalGroup();
    execute(
        "INSERT INTO capability_grants (id, organization_id, capability, subject_type,"
            + " subject_group_id) VALUES (gen_random_uuid(), '"
            + SEEDED_ORGANIZATION_ID
            + "', 'CREATE_CONNECTOR_LIBRARY', 'GROUP', '"
            + group
            + "')");
    execute(
        "INSERT INTO capability_grant_history (id, organization_id, capability, subject_type,"
            + " subject_group_id, cause, valid_from, valid_to) VALUES (gen_random_uuid(), '"
            + SEEDED_ORGANIZATION_ID
            + "', 'CREATE_CONNECTOR_LIBRARY', 'GROUP', '"
            + group
            + "', 'GRANTED', '2026-01-01T00:00:00Z', '2026-02-01T00:00:00Z')");
    long spaceGrants = countWhere("capability_grants", "capability = 'CREATE_SPACE'");

    applyChangelog(connection, FILE);

    String connector = "capability = 'CREATE_CONNECTOR_LIBRARY'";
    assertThat(countWhere("capability_grants", connector + " AND scope IS NULL")).isZero();
    assertThat(countWhere("capability_grant_history", connector + " AND scope IS NULL")).isZero();
    assertThat(
            scopes(
                "capability_grants",
                connector
                    + " AND subject_type = 'ALL_ACCOUNTS' AND organization_id = '"
                    + SEEDED_ORGANIZATION_ID
                    + "'"))
        .isEqualTo(TYPES);
    assertThat(scopes("capability_grants", connector + " AND subject_group_id = '" + group + "'"))
        .isEqualTo(TYPES);
    assertThat(
            scopes(
                "capability_grant_history",
                connector
                    + " AND subject_group_id = '"
                    + group
                    + "' AND valid_from = '2026-01-01T00:00:00Z'"
                    + " AND valid_to = '2026-02-01T00:00:00Z' AND cause = 'GRANTED'"))
        .as("the interval keeps its bounds and cause in every type")
        .isEqualTo(TYPES);
    assertThat(countWhere("capability_grants", "capability = 'CREATE_SPACE' AND scope IS NULL"))
        .isEqualTo(spaceGrants);
  }

  @Test
  void theScopeIsRequiredForTheConnectorCapabilityOnlyAndPartOfTheUniqueness() throws Exception {
    applyChangelog(connection, FILE);
    UUID user = insertUser();

    assertRejected(grant("CREATE_CONNECTOR_LIBRARY", null, user), "chk_capability_grants_scope");
    assertRejected(grant("CREATE_SPACE", "'TYPE:S3'", user), "chk_capability_grants_scope");
    execute(grant("CREATE_CONNECTOR_LIBRARY", "'PROFILE:a'", user));
    execute(grant("CREATE_CONNECTOR_LIBRARY", "'PROFILE:b'", user));
    assertRejected(
        grant("CREATE_CONNECTOR_LIBRARY", "'PROFILE:a'", user),
        "uk_capability_grants_user_subject");
    execute(grant("CREATE_SPACE", null, user));
    assertRejected(grant("CREATE_SPACE", null, user), "uk_capability_grants_user_subject");
  }

  @Test
  void aLaterOrganizationIsDeliveredTheConnectorCapabilityPerType() throws Exception {
    applyChangelog(connection, FILE);

    UUID organization = insertOrganization();

    String ofOrganization = "organization_id = '" + organization + "'";
    assertThat(
            scopes(
                "capability_grants",
                ofOrganization + " AND capability = 'CREATE_CONNECTOR_LIBRARY'"))
        .isEqualTo(TYPES);
    assertThat(
            scopes(
                "capability_grant_history",
                ofOrganization
                    + " AND capability = 'CREATE_CONNECTOR_LIBRARY'"
                    + " AND cause = 'DELIVERED' AND valid_to IS NULL"))
        .isEqualTo(TYPES);
    assertThat(countWhere("capability_grants", ofOrganization + " AND scope IS NULL"))
        .as("CREATE_SPACE, CREATE_LIBRARY and CREATE_PROMPT_LIBRARY stay unscoped")
        .isEqualTo(3);
  }

  private static String grant(String capability, String scope, UUID user) {
    return "INSERT INTO capability_grants (id, organization_id, capability, scope, subject_type,"
        + " subject_user_id) VALUES (gen_random_uuid(), '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + capability
        + "', "
        + (scope == null ? "NULL" : scope)
        + ", 'USER', '"
        + user
        + "')";
  }

  private List<String> scopes(String table, String where) throws Exception {
    return List.of(
        stringOf(
                "SELECT coalesce(string_agg(scope, ',' ORDER BY scope), '') FROM "
                    + table
                    + " WHERE "
                    + where)
            .split(","));
  }
}
