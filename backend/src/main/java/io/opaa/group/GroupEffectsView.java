package io.opaa.group;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * How far one group still reaches (ADR-0036, Entscheidung 2) - "wo wirkt diese Gruppe". Counts
 * only: the figures say how much work an ablösung is, while every single effect is removed at the
 * object it belongs to.
 *
 * @param scopedAuthorizations still-conferring diagnostic authorisations (ADR-0016) scoped to this
 *     group - they would cascade away with it, without the revocation event ADR-0016 requires
 */
public record GroupEffectsView(
    Group group,
    long assetGrants,
    long grantedAssets,
    long spaceMemberships,
    long spaces,
    long ownedAssets,
    long capabilities,
    long scopedAuthorizations) {

  /** Whether this group carries anything that has to be decided about before it disappears. */
  public boolean any() {
    return assetGrants > 0
        || spaceMemberships > 0
        || ownedAssets > 0
        || capabilities > 0
        || scopedAuthorizations > 0;
  }

  public UUID groupId() {
    return group.getId();
  }

  /** The German sentence the work list shows; empty exactly while {@link #any()} is false. */
  public String describe() {
    List<String> parts = new ArrayList<>();
    if (assetGrants > 0) {
      parts.add(
          (assetGrants == 1 ? "1 Berechtigung" : assetGrants + " Berechtigungen")
              + " an "
              + (grantedAssets == 1 ? "1 Objekt" : grantedAssets + " Objekten"));
    }
    if (spaceMemberships > 0) {
      parts.add(
          (spaceMemberships == 1
                  ? "1 Space-Mitgliedschaft"
                  : spaceMemberships + " Space-Mitgliedschaften")
              + " in "
              + (spaces == 1 ? "1 Space" : spaces + " Spaces"));
    }
    if (ownedAssets > 0) {
      parts.add(
          ownedAssets == 1
              ? "Eigentümerin von 1 Objekt"
              : "Eigentümerin von " + ownedAssets + " Objekten");
    }
    if (capabilities > 0) {
      parts.add(capabilities == 1 ? "1 Anlegerecht" : capabilities + " Anlegerechte");
    }
    if (scopedAuthorizations > 0) {
      parts.add(
          "Geltungsbereich von "
              + (scopedAuthorizations == 1
                  ? "1 Diagnose-Vollmacht"
                  : scopedAuthorizations + " Diagnose-Vollmachten"));
    }
    return String.join(", ", parts);
  }
}
