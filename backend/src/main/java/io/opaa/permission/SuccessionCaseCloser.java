package io.opaa.permission;

import io.opaa.api.types.SuccessionObjectType;
import java.util.UUID;

/**
 * Ends the open succession record of one object and names who ended it (#1819, ADR-0036
 * Entscheidung 6). Called by an operation that knows its actor - today the transfer of ownership
 * and responsibility (#1834), which <i>is</i> the Übernahme the list points at.
 *
 * <p>Without it the detection run would close the record within the hour, correctly but anonymously
 * - and "wer den Zustand beendet hat" is exactly what the record is for.
 */
public interface SuccessionCaseCloser {

  void closeFor(SuccessionObjectType objectType, UUID objectId, UUID endedByUserId);
}
