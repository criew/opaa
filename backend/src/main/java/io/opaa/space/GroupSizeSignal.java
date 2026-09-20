package io.opaa.space;

/**
 * The passive growth signal a group membership carries (ADR-0036, Entscheidung 9): how many active
 * accounts the group reached when it was admitted, and how many it reaches now - "Referat 50: 23
 * bei Erteilung, heute 41". No mail, no workflow; one line somebody reads who answers for the
 * admission.
 *
 * <p><b>The "kleine Gruppe" suppression applies to both figures at once</b>, and to the difference
 * with them: if either lies below {@link #MINIMUM_GROUP_SIZE}, neither is disclosed, because
 * publishing one of them beside the difference reconstructs the other. A group of four is, in a
 * unit of that size, a person with a name.
 *
 * <p><b>The minimum is a constant here, not a setting yet.</b> ADR-0036 makes it a governance
 * setting with an enforced lower bound of 5 and a default of 5 - the two values coincide, so the
 * delivered behaviour is identical either way, and the setting itself (with its governance event
 * and its administration surface) belongs to #1821. Until then this is the single place the value
 * is read, so making it configurable touches this class and nothing else.
 *
 * <p><b>Protected groups are not covered here.</b> For them the signal drops out entirely
 * (ADR-0036, Entscheidung 9) - the size is the actual disclosure there. The protection flag arrives
 * with #1814; until it exists this class cannot make that distinction, and does not pretend to.
 */
public record GroupSizeSignal(
    Integer memberCountAtGrant, Integer memberCountNow, boolean smallGroup, boolean emptyGroup) {

  /** ADR-0036, "Zahlen, die dieser ADR setzt": enforced lower bound and delivered default. */
  public static final int MINIMUM_GROUP_SIZE = 5;

  /** The signal for a person's membership - a person has no group size. */
  public static final GroupSizeSignal NONE = new GroupSizeSignal(null, null, false, false);

  public static GroupSizeSignal of(Integer atGrant, int now) {
    boolean small = now < MINIMUM_GROUP_SIZE || (atGrant != null && atGrant < MINIMUM_GROUP_SIZE);
    return new GroupSizeSignal(
        small ? null : atGrant, small ? null : Integer.valueOf(now), small, now == 0);
  }
}
