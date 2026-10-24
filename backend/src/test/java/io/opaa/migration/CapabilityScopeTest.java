package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The scope of {@code CREATE_CONNECTOR_LIBRARY} (ADR-0036, Entscheidung 5): it is required for that
 * capability only and part of the uniqueness, and a new organization is delivered the capability
 * once per connector type.
 */
class CapabilityScopeTest extends AbstractBaselineTest {

  private static final List<String> TYPES =
      List.of(
          "TYPE:CONFLUENCE", "TYPE:FILESYSTEM", "TYPE:HTTP_DIRECTORY", "TYPE:RSS_FEED", "TYPE:S3");

  @Test
  void theScopeIsRequiredForTheConnectorCapabilityOnlyAndPartOfTheUniqueness() throws Exception {
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
  void aNewOrganizationIsDeliveredTheConnectorCapabilityPerType() throws Exception {
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
