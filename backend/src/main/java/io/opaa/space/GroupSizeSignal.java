package io.opaa.space;

import io.opaa.permission.GroupSizeProperties;

/**
 * The passive growth signal a group membership carries (ADR-0036, Entscheidung 9): how many active
 * accounts the group reached when it was admitted, and how many it reaches now - "Referat 50: 23
 * bei Erteilung, heute 41". No mail, no workflow; one line somebody reads who answers for the
 * admission.
 *
 * <p><b>The "kleine Gruppe" suppression applies to both figures at once</b>, and to the difference
 * with them: if either lies below {@link #MINIMUM_GROUP_SIZE}, neither <em>figure</em> is
 * disclosed, because publishing one of them beside the difference reconstructs the other. A group
 * of four is, in a unit of that size, a person with a name.
 *
 * <p><b>One size does stay visible, on purpose:</b> {@link #emptyGroup} says that the group reaches
 * nobody, and that is the number zero. ADR-0036, Entscheidung 6 requires the warning - an empty
 * effective group is admitted deliberately - and the suppression protects members from being
 * identifiable, of which an empty group has none.
 *
 * <p><b>This suppression always uses the enforced lower bound</b>, not the configured value: a
 * house that raises the Mindestgruppengröße for the Diagnose (#1835, {@link GroupSizeProperties})
 * decides about a rights context there, while this signal only decides whether a size figure is
 * shown beside an admission. Its governance surface belongs to #1821.
 *
 * <p><b>Protected groups are not covered here.</b> For them the signal drops out entirely
 * (ADR-0036, Entscheidung 9) - the size is the actual disclosure there. The protection flag arrives
 * with #1814; until it exists this class cannot make that distinction, and does not pretend to.
 */
public record GroupSizeSignal(
    Integer memberCountAtGrant, Integer memberCountNow, boolean smallGroup, boolean emptyGroup) {

  /**
   * ADR-0036, "Zahlen, die dieser ADR setzt". The one place the number lives is {@link
   * GroupSizeProperties#ENFORCED_MINIMUM} since #1835, which also holds the configurable value and
   * refuses a start below the bound.
   */
  public static final int MINIMUM_GROUP_SIZE = GroupSizeProperties.ENFORCED_MINIMUM;

  /** The signal for a person's membership - a person has no group size. */
  public static final GroupSizeSignal NONE = new GroupSizeSignal(null, null, false, false);

  public static GroupSizeSignal of(Integer atGrant, int now) {
    boolean small = now < MINIMUM_GROUP_SIZE || (atGrant != null && atGrant < MINIMUM_GROUP_SIZE);
    return new GroupSizeSignal(
        small ? null : atGrant, small ? null : Integer.valueOf(now), small, now == 0);
  }
}
