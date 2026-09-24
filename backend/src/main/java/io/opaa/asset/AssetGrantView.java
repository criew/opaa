package io.opaa.asset;

import io.opaa.permission.AssetGrant;
import io.opaa.permission.GroupSizeSignal;

/**
 * A {@link AssetGrant} enriched with the subject's and granter's display names, resolved by {@link
 * AssetGrantService#toViews} (#423) - the domain counterpart of the generated {@code
 * AssetGrantResponse}, mapped onto it by {@code io.opaa.api.AssetGrantResponseMapper}.
 *
 * @param subjectDisplayName {@code null} if the subject row itself no longer exists, and {@code
 *     null} for a protected group, which appears in another person's list as a nameless row
 *     (ADR-0036, Entscheidung 9); {@code protectedGroup} tells the two apart.
 * @param grantedByDisplayName display name of whoever conferred the grant's current role (see
 *     {@link AssetGrant#updateRole}); {@code null} when the grant carries no {@code
 *     grantedByUserId} (a historical row from before that field existed) or that user no longer
 *     exists.
 * @param groupSize the growth signal of ADR-0036, Entscheidung 9; {@link GroupSizeSignal#NONE} for
 *     a person and for a protected group, where the size is the actual disclosure.
 */
public record AssetGrantView(
    AssetGrant grant,
    String subjectDisplayName,
    String grantedByDisplayName,
    boolean protectedGroup,
    GroupSizeSignal groupSize) {

  /** The label "Alle Konten" carries wherever a grant names its recipient. */
  public static final String ALL_ACCOUNTS_LABEL = "Alle Konten";

  /** A grant to every account - a fixed label, no group size, no protection. */
  public static AssetGrantView ofAllAccounts(AssetGrant grant, String grantedByDisplayName) {
    return new AssetGrantView(
        grant, ALL_ACCOUNTS_LABEL, grantedByDisplayName, false, GroupSizeSignal.NONE);
  }

  /** A person's grant - no group size, no protection. */
  public static AssetGrantView ofUser(
      AssetGrant grant, String subjectDisplayName, String grantedByDisplayName) {
    return new AssetGrantView(
        grant, subjectDisplayName, grantedByDisplayName, false, GroupSizeSignal.NONE);
  }

  /** A group's grant; a protected group is named to nobody here. */
  public static AssetGrantView ofGroup(
      AssetGrant grant,
      String groupName,
      String grantedByDisplayName,
      boolean protectedGroup,
      GroupSizeSignal groupSize) {
    return new AssetGrantView(
        grant, protectedGroup ? null : groupName, grantedByDisplayName, protectedGroup, groupSize);
  }
}
