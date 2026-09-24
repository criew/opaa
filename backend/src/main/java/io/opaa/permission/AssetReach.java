package io.opaa.permission;

import io.opaa.api.types.AssetGrantSubjectType;

/**
 * How far one asset reaches right now, derived from its unexpired grants rather than stored (#1931,
 * ADR-0037 Entscheidung 9) - the three figures an overview turns into a badge ("Alle", "3 Gruppen,
 * 2 Personen", "nur Sie"). {@link #allAccounts} outranks both counts in any wording.
 *
 * @param userCount includes the owner's own grant, which every asset carries.
 */
public record AssetReach(boolean allAccounts, int groupCount, int userCount) {

  /** An asset no grant reaches - the starting point every counted asset is built up from. */
  public static final AssetReach NONE = new AssetReach(false, 0, 0);

  /** This reach with {@code count} further grants of {@code subjectType} counted in. */
  public AssetReach plus(AssetGrantSubjectType subjectType, int count) {
    return switch (subjectType) {
      case USER -> new AssetReach(allAccounts, groupCount, userCount + count);
      case GROUP -> new AssetReach(allAccounts, groupCount + count, userCount);
      case ALL_ACCOUNTS -> new AssetReach(count > 0, groupCount, userCount);
    };
  }
}
