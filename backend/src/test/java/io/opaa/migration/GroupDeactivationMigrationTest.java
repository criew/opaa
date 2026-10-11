package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The deactivation switch of ADR-0036, Entscheidung 14, applied to an existing installation: every
 * group already there stays in effect, the switch is refused for a provider group and without its
 * moment, and the membership history accepts the two new causes while keeping its rows.
 */
class GroupDeactivationMigrationTest extends AbstractBaselineTest {

  static final String FILE = "db/changelog/rights/2026-10-11-group-deactivation.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void existingGroupsStayInEffectAndKeepTheirHistory() throws Exception {
    UUID internal = insertInternalGroup();
    UUID provider = insertProvider();
    UUID providerGroup = insertGroup("IDENTITY_PROVIDER", provider, "Referat 50");
    UUID member = insertUser();
    execute(history(internal, member, "ADDED"));

    applyChangelog(connection, FILE);

    assertThat(
            countWhere(
                "groups", "deactivated AND id IN ('" + internal + "', '" + providerGroup + "')"))
        .isZero();
    assertThat(countWhere("groups", "deactivated_at IS NOT NULL AND id = '" + internal + "'"))
        .isZero();
    assertThat(countWhere("group_membership_history", "group_id = '" + internal + "'"))
        .isEqualTo(1);
  }

  @Test
  void onlyAnInternalGroupCanBeDeactivatedAndOnlyWithItsMoment() throws Exception {
    applyChangelog(connection, FILE);
    UUID internal = insertInternalGroup();
    UUID providerGroup = insertGroup("IDENTITY_PROVIDER", insertProvider(), "Referat 51");

    execute(
        "UPDATE groups SET deactivated = true, deactivated_at = now() WHERE id = '"
            + internal
            + "'");
    assertThat(countWhere("groups", "deactivated AND id = '" + internal + "'")).isEqualTo(1);

    assertRejected(
        "UPDATE groups SET deactivated = true, deactivated_at = now() WHERE id = '"
            + providerGroup
            + "'",
        "chk_groups_deactivated_internal");
    assertRejected(
        "UPDATE groups SET deactivated = false WHERE id = '" + internal + "'",
        "chk_groups_deactivated_at");
  }

  @Test
  void theMembershipHistoryAcceptsTheTwoNewCausesAndNoUnknownOne() throws Exception {
    applyChangelog(connection, FILE);
    UUID group = insertInternalGroup();
    UUID member = insertUser();

    execute(history(group, member, "GROUP_DEACTIVATED"));
    execute(history(group, member, "GROUP_REACTIVATED"));
    assertThat(countWhere("group_membership_history", "group_id = '" + group + "'")).isEqualTo(2);

    assertRejected(history(group, member, "PAUSED"), "chk_group_membership_history_cause");
  }

  /** A closed membership interval of the given cause, the shape every writer leaves behind. */
  static String history(UUID group, UUID user, String cause) {
    return "INSERT INTO group_membership_history (id, group_id, organization_id, user_id, cause,"
        + " valid_from, valid_to) VALUES (gen_random_uuid(), '"
        + group
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "', '"
        + user
        + "', '"
        + cause
        + "', now() - interval '1 day', now())";
  }
}
