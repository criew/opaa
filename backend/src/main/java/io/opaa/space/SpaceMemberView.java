package io.opaa.space;

import io.opaa.permission.GroupAttribution;
import io.opaa.permission.GroupSizeSignal;

/**
 * A space membership enriched with what the row shows but the entity does not carry: the subject's
 * display name (a person's, or the group's) and, for a group, the growth signal of ADR-0036,
 * Entscheidung 9. Domain counterpart of the generated {@code SpaceMemberResponse}, mapped by {@code
 * io.opaa.api.SpaceResponseMapper}.
 *
 * @param displayName null for a protected group: in another person's list it is a nameless row, and
 *     {@code protectedGroup} is what tells that apart from a group row whose group is gone.
 */
public record SpaceMemberView(
    SpaceMembership membership,
    String displayName,
    GroupSizeSignal groupSize,
    boolean protectedGroup) {

  /** A person's row - no group size, no protection. */
  public static SpaceMemberView ofUser(SpaceMembership membership, String displayName) {
    return new SpaceMemberView(membership, displayName, GroupSizeSignal.NONE, false);
  }

  /**
   * A group's row. A protected group is named to nobody here and carries no growth signal - there
   * the size is the actual disclosure (ADR-0036, Entscheidung 9); the row itself stays, so an ADMIN
   * can end a membership they cannot see.
   */
  public static SpaceMemberView ofGroup(
      SpaceMembership membership, GroupAttribution group, GroupSizeSignal groupSize) {
    boolean protectedGroup = group != null && group.protectedGroup();
    return new SpaceMemberView(
        membership,
        protectedGroup ? null : group == null ? null : group.name(),
        protectedGroup ? GroupSizeSignal.NONE : groupSize,
        protectedGroup);
  }
}
