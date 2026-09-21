package io.opaa.permission;

/**
 * The passive growth signal a group's grant or membership carries (ADR-0036, Entscheidung 9): how
 * many active accounts the group reached when it was admitted, and how many it reaches now -
 * "Referat 50: 23 bei Erteilung, heute 41". No mail, no workflow; one line somebody reads who
 * answers for the admission.
 *
 * <p><b>The "kleine Gruppe" suppression applies to both figures at once</b>, and to the difference
 * with them: if either lies below the installation's Mindestgruppengröße, neither <em>figure</em>
 * is disclosed, because publishing one of them beside the difference reconstructs the other. A
 * group of four is, in a unit of that size, a person with a name.
 *
 * <p><b>One size does stay visible, on purpose:</b> {@link #emptyGroup} says that the group reaches
 * nobody, and that is the number zero. ADR-0036, Entscheidung 6 requires the warning - an empty
 * effective group is admitted deliberately - and the suppression protects members from being
 * identifiable, of which an empty group has none.
 *
 * <p><b>Protected groups are not covered here.</b> For them the signal drops out entirely
 * (ADR-0036, Entscheidung 9) - the size is the actual disclosure there; the caller hands out {@link
 * #NONE} instead of asking for a signal at all.
 */
public record GroupSizeSignal(
    Integer memberCountAtGrant, Integer memberCountNow, boolean smallGroup, boolean emptyGroup) {

  /** The signal for a person's membership - a person has no group size. */
  public static final GroupSizeSignal NONE = new GroupSizeSignal(null, null, false, false);

  /**
   * @param minimumGroupSize the installation's Mindestgruppengröße ({@link
   *     GroupSizeProperties#minimumGroupSize()}), never its enforced lower bound: a house that
   *     raises the value raises the suppression with it (ADR-0036, Personalrat A2).
   */
  public static GroupSizeSignal of(Integer atGrant, int now, int minimumGroupSize) {
    boolean small = now < minimumGroupSize || (atGrant != null && atGrant < minimumGroupSize);
    return new GroupSizeSignal(
        small ? null : atGrant, small ? null : Integer.valueOf(now), small, now == 0);
  }
}
