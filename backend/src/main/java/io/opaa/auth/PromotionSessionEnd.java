package io.opaa.auth;

import java.util.UUID;

/**
 * Ends every session of an account that becomes a system administrator, so the account signs in
 * again and its sessions run under the administrators' lifetimes from the first token on.
 * Implemented by the local accounts; an account signing in through an identity provider holds no
 * session here and is left as it is.
 */
public interface PromotionSessionEnd {

  /**
   * Ends every session of {@code user}'s local account and what a session could have created, in
   * the caller's transaction; {@code actorUserId} is the person whose act grants the role.
   */
  void endSessionsOfPromoted(User user, UUID actorUserId);
}
