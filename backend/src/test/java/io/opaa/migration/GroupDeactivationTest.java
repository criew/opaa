package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The deactivation switch of ADR-0036, Entscheidung 14: a new group is in effect, the switch is
 * refused for a provider group and without its moment, and the membership history accepts the
 * causes of a deactivation and a reactivation.
 */
class GroupDeactivationTest extends AbstractBaselineTest {

  @Test
  void onlyAnInternalGroupCanBeDeactivatedAndOnlyWithItsMoment() throws Exception {
    UUID internal = insertInternalGroup();
    UUID providerGroup = insertGroup("IDENTITY_PROVIDER", insertProvider(), "Referat 51");
    assertThat(
            countWhere(
                "groups",
                "(deactivated OR deactivated_at IS NOT NULL) AND id IN ('"
                    + internal
                    + "', '"
                    + providerGroup
                    + "')"))
        .as("a new group is in effect")
        .isZero();

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
  void theMembershipHistoryAcceptsTheDeactivationCausesAndNoUnknownOne() throws Exception {
    UUID group = insertInternalGroup();
    UUID member = insertUser();

    execute(history(group, member, "GROUP_DEACTIVATED"));
    execute(history(group, member, "GROUP_REACTIVATED"));
    assertThat(countWhere("group_membership_history", "group_id = '" + group + "'")).isEqualTo(2);

    assertRejected(history(group, member, "PAUSED"), "chk_group_membership_history_cause");
  }

  /** A closed membership interval of the given cause, the shape every writer leaves behind. */
  private static String history(UUID group, UUID user, String cause) {
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
