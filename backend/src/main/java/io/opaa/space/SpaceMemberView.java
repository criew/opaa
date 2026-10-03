package io.opaa.space;

import io.opaa.permission.GroupAttribution;

/**
 * A space membership enriched with what the row shows but the entity does not carry: the subject's
 * display name (a person's, or the group's) and, for a group, its current size. Domain counterpart
 * of the generated {@code SpaceMemberResponse}, mapped by {@code SpaceResponseMapper}.
 *
 * @param displayName null for a protected group: in another person's list it is a nameless row, and
 *     {@code protectedGroup} is what tells that apart from a group row whose group is gone.
 * @param activeMemberCount the group's active accounts right now; null for a person and for a
 *     protected group.
 */
public record SpaceMemberView(
    SpaceMembership membership,
    String displayName,
    Integer activeMemberCount,
    boolean protectedGroup) {

  /** A person's row - no group size, no protection. */
  public static SpaceMemberView ofUser(SpaceMembership membership, String displayName) {
    return new SpaceMemberView(membership, displayName, null, false);
  }

  /**
   * A group's row. A protected group is named to nobody here and carries no size - there the size
   * is the actual disclosure (ADR-0036, Entscheidung 9); the row itself stays, so an ADMIN can end
   * a membership they cannot see.
   */
  public static SpaceMemberView ofGroup(
      SpaceMembership membership, GroupAttribution group, int activeMemberCount) {
    boolean protectedGroup = group != null && group.protectedGroup();
    return new SpaceMemberView(
        membership,
        protectedGroup ? null : group == null ? null : group.name(),
        protectedGroup ? null : activeMemberCount,
        protectedGroup);
  }
}
