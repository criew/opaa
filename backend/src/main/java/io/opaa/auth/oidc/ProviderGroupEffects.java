package io.opaa.auth.oidc;

/**
 * How far the groups of one identity provider still reach (ADR-0036, Entscheidung 2). The counts
 * are what the 409 of a refused provider deletion quotes, so an administrator sees the size of the
 * work ahead instead of a bare refusal.
 *
 * @param groups how many of the provider's groups carry at least one of the effects below
 * @param grants how many grants those groups hold
 * @param grantedAssets across how many distinct assets those grants run
 * @param owningGroups how many of those groups own an asset themselves
 */
public record ProviderGroupEffects(
    long groups, long grants, long grantedAssets, long owningGroups) {

  public static final ProviderGroupEffects NONE = new ProviderGroupEffects(0, 0, 0, 0);

  public boolean any() {
    return groups > 0;
  }

  /** The German sentence the refusal carries; only meaningful while {@link #any()}. */
  public String describe() {
    StringBuilder text = new StringBuilder();
    text.append(groups == 1 ? "1 Gruppe wirkt noch" : groups + " Gruppen wirken noch");
    if (grants > 0) {
      text.append(": ")
          .append(grants == 1 ? "1 Berechtigung" : grants + " Berechtigungen")
          .append(" an ")
          .append(grantedAssets == 1 ? "1 Objekt" : grantedAssets + " Objekten");
    }
    if (owningGroups > 0) {
      text.append(grants > 0 ? ", " : ": ")
          .append(
              owningGroups == 1
                  ? "1 Gruppe ist Eigentümerin eines Objekts"
                  : owningGroups + " Gruppen sind Eigentümerin eines Objekts");
    }
    return text.append(".").toString();
  }
}
