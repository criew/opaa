package io.opaa.permission;

import io.opaa.api.types.SuccessionAddressee;
import io.opaa.api.types.SuccessionObjectType;
import java.util.List;
import java.util.UUID;

/**
 * One object a {@link SuccessionFindingSource} currently reports - the state as it is derived right
 * now, without the {@code first_seen_at} the detection run keeps for it.
 *
 * @param assetType the type of the asset - set exactly when {@code objectType} is {@link
 *     SuccessionObjectType#ASSET}
 * @param objectName the name as it stands today; a protected group is named by its protection alone
 * @param ownerHint who holds the object today, for the administration's orientation - readable in
 *     the list, never a query parameter, a sort key or a count (Personalrat E1, Z7)
 * @param membershipHints the directory groups the departed person belonged to ("war Mitglied von
 *     Referat 50") - Bestandsinformation as a suggestion where to look, never an activity
 *     evaluation (Personalrat E4)
 * @param affectedObjects how many objects hang on this finding - the figure the tab "Freigaben ohne
 *     Empfänger" needs; zero where the finding is the object itself
 */
public record SuccessionFinding(
    SuccessionObjectType objectType,
    AssetType assetType,
    UUID objectId,
    String objectName,
    SuccessionAddressee addressee,
    String ownerHint,
    List<String> membershipHints,
    int affectedObjects) {

  public SuccessionFinding {
    if ((objectType == SuccessionObjectType.ASSET) != (assetType != null)) {
      throw new IllegalArgumentException(
          "assetType must be set exactly for objectType ASSET, got " + objectType);
    }
  }

  /** A finding about a space or a group. */
  public static SuccessionFinding of(
      SuccessionObjectType objectType,
      UUID objectId,
      String objectName,
      SuccessionAddressee addressee) {
    return new SuccessionFinding(
        objectType, null, objectId, objectName, addressee, null, List.of(), 0);
  }

  /** A finding about an asset of any type. */
  public static SuccessionFinding ofAsset(
      AssetType assetType, UUID assetId, String objectName, SuccessionAddressee addressee) {
    return new SuccessionFinding(
        SuccessionObjectType.ASSET, assetType, assetId, objectName, addressee, null, List.of(), 0);
  }

  public SuccessionFinding withOwnerHint(String ownerHint) {
    return new SuccessionFinding(
        objectType,
        assetType,
        objectId,
        objectName,
        addressee,
        ownerHint,
        membershipHints,
        affectedObjects);
  }

  public SuccessionFinding withMembershipHints(List<String> hints) {
    return new SuccessionFinding(
        objectType,
        assetType,
        objectId,
        objectName,
        addressee,
        ownerHint,
        List.copyOf(hints),
        affectedObjects);
  }

  public SuccessionFinding withAffectedObjects(int affectedObjects) {
    return new SuccessionFinding(
        objectType,
        assetType,
        objectId,
        objectName,
        addressee,
        ownerHint,
        membershipHints,
        affectedObjects);
  }
}
