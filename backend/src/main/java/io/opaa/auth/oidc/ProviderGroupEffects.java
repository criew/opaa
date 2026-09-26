package io.opaa.auth.oidc;

import java.util.ArrayList;
import java.util.List;

/**
 * How far the groups of one identity provider still reach (ADR-0036, Entscheidung 2). The counts
 * are what the 409 of a refused provider deletion quotes, so an administrator sees the size of the
 * work ahead instead of a bare refusal.
 *
 * @param groups how many of the provider's groups carry at least one of the effects below
 * @param grants how many grants those groups hold
 * @param grantedAssets across how many distinct assets those grants run
 * @param owningGroups how many of those groups own an asset themselves
 * @param spaceMemberships how many space memberships those groups hold (#1815) - {@code
 *     space_memberships.group_id} is {@code ON DELETE RESTRICT}, so without this count the deletion
 *     would run into the foreign key instead of into the refusal
 * @param spaces across how many distinct spaces those memberships run
 * @param scopedAuthorizations how many still-conferring diagnostic authorisations (ADR-0016) name
 *     one of those groups as their scope - they would cascade away with the group, without the
 *     revocation event ADR-0016 requires
 */
public record ProviderGroupEffects(
    long groups,
    long grants,
    long grantedAssets,
    long owningGroups,
    long spaceMemberships,
    long spaces,
    long scopedAuthorizations) {

  public static final ProviderGroupEffects NONE = new ProviderGroupEffects(0, 0, 0, 0, 0, 0, 0);

  public boolean any() {
    return groups > 0;
  }

  /**
   * The German sentence the refusal carries, in the words of the group administration's column
   * „Verwendung": every asset a group reaches is a library. Only meaningful while {@link #any()}.
   */
  public String describe() {
    String lead =
        groups == 1 ? "1 Gruppe wird noch verwendet" : groups + " Gruppen werden noch verwendet";
    List<String> parts = new ArrayList<>();
    if (grants > 0) {
      parts.add("Rechte an " + libraries(grantedAssets));
    }
    if (owningGroups > 0) {
      parts.add(
          owningGroups == 1
              ? "1 Gruppe ist Eigentümerin einer Bibliothek"
              : owningGroups + " Gruppen sind Eigentümerinnen von Bibliotheken");
    }
    if (spaceMemberships > 0) {
      parts.add("Mitglied in " + (spaces == 1 ? "1 Space" : spaces + " Spaces"));
    }
    if (scopedAuthorizations > 0) {
      parts.add(
          "Geltungsbereich von "
              + (scopedAuthorizations == 1
                  ? "1 Diagnose-Vollmacht"
                  : scopedAuthorizations + " Diagnose-Vollmachten"));
    }
    return parts.isEmpty() ? lead + "." : lead + ": " + String.join(", ", parts) + ".";
  }

  private static String libraries(long count) {
    return count == 1 ? "1 Bibliothek" : count + " Bibliotheken";
  }
}
