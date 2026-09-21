package io.opaa.space;

/**
 * A space membership enriched with what the row shows but the entity does not carry: the subject's
 * display name (a person's, or the group's) and, for a group, the growth signal of ADR-0036,
 * Entscheidung 9. Domain counterpart of the generated {@code SpaceMemberResponse}, mapped by {@code
 * io.opaa.api.SpaceResponseMapper}.
 */
public record SpaceMemberView(
    SpaceMembership membership, String displayName, GroupSizeSignal groupSize) {

  /** A person's row - no group size. */
  public static SpaceMemberView ofUser(SpaceMembership membership, String displayName) {
    return new SpaceMemberView(membership, displayName, GroupSizeSignal.NONE);
  }
}
