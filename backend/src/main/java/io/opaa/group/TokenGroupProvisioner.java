package io.opaa.group;

import io.opaa.auth.UserProvisionedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Applies the token's groups claim to the signing-in account (#1423), for the sign-ins whose
 * provider declares one - see {@link TokenGroupSynchronizer} for what applying means.
 */
@Component
class TokenGroupProvisioner {

  private final TokenGroupSynchronizer synchronizer;

  TokenGroupProvisioner(TokenGroupSynchronizer synchronizer) {
    this.synchronizer = synchronizer;
  }

  /**
   * A failure is deliberately not swallowed: it fails the signing-in request, which is the only way
   * to keep that request from being authorized against memberships the token no longer backs. The
   * account stays usable - the next request re-runs the synchronization.
   */
  @Order(UserProvisionedEvent.TOKEN_GROUPS_ORDER)
  @EventListener
  void onUserProvisioned(UserProvisionedEvent event) {
    if (!event.hasTokenGroups()) {
      return;
    }
    synchronizer.apply(event.user(), event.provider(), event.tokenGroups());
  }
}
