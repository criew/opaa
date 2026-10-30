package io.opaa.auth;

import java.util.Optional;
import java.util.UUID;

/**
 * Ends every credential of an account that receives the AUDITOR role and opens a new one only its
 * person will hold. Implemented by the local accounts; an account signing in through an identity
 * provider holds no credentials here and gets {@link Optional#empty()}.
 */
public interface AuditorCredentialRenewal {

  /**
   * Discards the password, ends every session and open link of {@code user}'s local account and
   * returns the link that sets a new password - for the caller to hand over outside every channel
   * the system administration controls. Runs in the caller's transaction; {@code actorUserId} is
   * the person whose act grants the role.
   */
  Optional<String> renew(User user, UUID actorUserId);
}
