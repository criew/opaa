package io.opaa.permission;

/**
 * How many rows a transfer moves - the figures the preview shows and the audit entry carries ("12
 * Berechtigungen an 7 Bibliotheken, Mitglied in 2 Spaces, Eigentümerin von 3 Bibliotheken"). A part
 * outside the requested scope counts zero, so preview and result read the same.
 */
public record PermissionTransferCounts(
    int assetGrants, int spaceMemberships, int capabilities, int ownedAssets, int stewardships) {

  public static final PermissionTransferCounts NONE = new PermissionTransferCounts(0, 0, 0, 0, 0);

  public int total() {
    return assetGrants + spaceMemberships + capabilities + ownedAssets + stewardships;
  }
}
