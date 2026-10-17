package io.opaa.permission;

import io.opaa.api.types.SuccessionAddressee;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.api.types.SuccessionOwnerType;
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
 * @param ownerType the kind of owner of an asset, {@code null} for a space or a group - the kind
 *     only, never a query axis (Personalrat E1)
 * @param ownerGroupId the owning group, set exactly when {@code ownerType} is {@link
 *     SuccessionOwnerType#GROUP} - the source a transfer of the asset names
 */
public record SuccessionFinding(
    SuccessionObjectType objectType,
    AssetType assetType,
    UUID objectId,
    String objectName,
    SuccessionAddressee addressee,
    String ownerHint,
    List<String> membershipHints,
    int affectedObjects,
    SuccessionOwnerType ownerType,
    UUID ownerGroupId) {

  public SuccessionFinding {
    if ((objectType == SuccessionObjectType.ASSET) != (assetType != null)) {
      throw new IllegalArgumentException(
          "assetType must be set exactly for objectType ASSET, got " + objectType);
    }
    if ((ownerType == SuccessionOwnerType.GROUP) != (ownerGroupId != null)) {
      throw new IllegalArgumentException(
          "ownerGroupId must be set exactly for ownerType GROUP, got " + ownerType);
    }
  }

  /** A finding about a space or a group. */
  public static SuccessionFinding of(
      SuccessionObjectType objectType,
      UUID objectId,
      String objectName,
      SuccessionAddressee addressee) {
    return new SuccessionFinding(
        objectType, null, objectId, objectName, addressee, null, List.of(), 0, null, null);
  }

  /** The German wording of the addressee - the same one the list and the object's view use. */
  public String addresseeLabel() {
    return switch (addressee) {
      case SPACE_ADMINS -> "die übrigen handlungsfähigen ADMIN-Mitglieder des Space";
      case GROUP_STEWARDS -> "die Verantwortlichen der besitzenden Gruppe";
      case SYSTEM_ADMINISTRATION -> "die Systemverwaltung";
    };
  }

  /** A finding about an asset of any type. */
  public static SuccessionFinding ofAsset(
      AssetType assetType, UUID assetId, String objectName, SuccessionAddressee addressee) {
    return new SuccessionFinding(
        SuccessionObjectType.ASSET,
        assetType,
        assetId,
        objectName,
        addressee,
        null,
        List.of(),
        0,
        null,
        null);
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
        affectedObjects,
        ownerType,
        ownerGroupId);
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
        affectedObjects,
        ownerType,
        ownerGroupId);
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
        affectedObjects,
        ownerType,
        ownerGroupId);
  }

  /**
   * The kind of owner of an asset, and the group when a group owns it - the group is the source a
   * transfer names; a person is never identified this way.
   */
  public SuccessionFinding withOwner(SuccessionOwnerType ownerType, UUID ownerGroupId) {
    return new SuccessionFinding(
        objectType,
        assetType,
        objectId,
        objectName,
        addressee,
        ownerHint,
        membershipHints,
        affectedObjects,
        ownerType,
        ownerGroupId);
  }
}
