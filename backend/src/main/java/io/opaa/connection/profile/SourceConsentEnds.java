package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionEndCause;
import java.util.UUID;

/**
 * The port through which the profile administration ends the libraries' own consents on a profile
 * after it discarded their grants: each connection names why it ended, is logged and told to those
 * responsible. Answered above this package.
 */
public interface SourceConsentEnds {

  void endAllUnder(UUID profileId, ConnectionEndCause cause, UUID actorUserId);
}
