package io.opaa.permission;

import java.util.UUID;

/**
 * Ends the open succession record of one object and names who ended it (#1819, ADR-0036
 * Entscheidung 6). Called by an operation that knows its actor - today the transfer of ownership
 * and responsibility (#1834), which <i>is</i> the Übernahme the list points at.
 *
 * <p>Without it the detection run would close the record within the hour, correctly but anonymously
 * - and "wer den Zustand beendet hat" is exactly what the record is for. An operation that did
 * <b>not</b> end the state leaves the record alone: a record closed and reopened would restart the
 * age the list shows.
 *
 * <p>The asset half takes the open {@link AssetType}, not a closed enum of object types: a type
 * nobody answers for simply has no record to close (#1726).
 */
public interface SuccessionCaseCloser {

  void closeForAsset(AssetType assetType, UUID assetId, UUID endedByUserId);

  void closeForGroup(UUID groupId, UUID endedByUserId);
}
