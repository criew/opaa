package io.opaa.permission;

import io.opaa.api.types.SuccessionAddressee;
import io.opaa.api.types.SuccessionObjectType;
import java.util.List;
import java.util.UUID;

/**
 * One object a {@link SuccessionFindingSource} currently reports - the state as it is derived right
 * now, without the {@code first_seen_at} the detection run keeps for it.
 *
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
    UUID objectId,
    String objectName,
    SuccessionAddressee addressee,
    String ownerHint,
    List<String> membershipHints,
    int affectedObjects) {

  public static SuccessionFinding of(
      SuccessionObjectType objectType,
      UUID objectId,
      String objectName,
      SuccessionAddressee addressee) {
    return new SuccessionFinding(objectType, objectId, objectName, addressee, null, List.of(), 0);
  }

  public SuccessionFinding withOwnerHint(String ownerHint) {
    return new SuccessionFinding(
        objectType, objectId, objectName, addressee, ownerHint, membershipHints, affectedObjects);
  }

  public SuccessionFinding withMembershipHints(List<String> hints) {
    return new SuccessionFinding(
        objectType,
        objectId,
        objectName,
        addressee,
        ownerHint,
        List.copyOf(hints),
        affectedObjects);
  }

  public SuccessionFinding withAffectedObjects(int affectedObjects) {
    return new SuccessionFinding(
        objectType, objectId, objectName, addressee, ownerHint, membershipHints, affectedObjects);
  }
}
