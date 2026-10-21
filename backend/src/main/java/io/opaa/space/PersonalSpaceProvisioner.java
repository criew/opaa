package io.opaa.space;

import io.opaa.auth.UserProvisionedEvent;
import io.opaa.observability.AuthMetrics;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Gives every provisioned account its personal space, on every sign-in rather than only on the
 * first: {@link SpaceService#ensureDefaultSpace} is idempotent, so an account whose space failed to
 * appear earlier gets one on its next sign-in instead of staying without one.
 */
@Component
class PersonalSpaceProvisioner {

  private static final Logger log = LoggerFactory.getLogger(PersonalSpaceProvisioner.class);

  private final SpaceService spaceService;
  private final AuthMetrics authMetrics;

  PersonalSpaceProvisioner(SpaceService spaceService, AuthMetrics authMetrics) {
    this.spaceService = spaceService;
    this.authMetrics = authMetrics;
  }

  /**
   * Ordered ahead of the rights-affecting listeners of the same event, so a failure there cannot
   * leave the account without a personal space.
   */
  @Order(UserProvisionedEvent.PERSONAL_SPACE_ORDER)
  @EventListener
  void onUserProvisioned(UserProvisionedEvent event) {
    provision(event.user().getId(), event.user().getOrganizationId(), event.createdHere());
  }

  /**
   * A sign-in publishes outside any transaction (see {@code UserService#findOrCreateUser}); {@link
   * SpaceService#ensureDefaultSpace} then inserts on its own connection. A publisher that holds a
   * transaction - the administration creating an account - gets the space in that transaction
   * instead: a failure there fails the creation and leaves nothing half done.
   */
  private void provision(UUID userId, UUID organizationId, boolean createdHere) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      spaceService.ensureDefaultSpaceInCurrentTransaction(userId, organizationId);
      return;
    }
    ensurePersonalSpace(userId, organizationId, createdHere);
  }

  /**
   * A failure is counted and logged, never rethrown: this runs on every sign-in, so a rethrow would
   * turn a failing provisioning into a permanent lockout instead of a sign-in without (yet) a
   * personal space - the next sign-in retries.
   *
   * <p>{@code createdHere} selects the fast path for an account this very sign-in created: it
   * cannot already own a personal space, so {@link SpaceService#ensureDefaultSpaceForNewUser} skips
   * the existence check that only {@link SpaceService#ensureDefaultSpace} is allowed to omit.
   */
  private void ensurePersonalSpace(UUID userId, UUID organizationId, boolean createdHere) {
    try {
      if (createdHere) {
        spaceService.ensureDefaultSpaceForNewUser(userId, organizationId);
      } else {
        spaceService.ensureDefaultSpace(userId, organizationId);
      }
    } catch (RuntimeException e) {
      authMetrics.recordPersonalSpaceProvisioningFailed();
      log.error(
          "Failed to provision personal space for user {} (organization {}); will retry on next"
              + " login (failure #{} since startup)",
          userId,
          organizationId,
          (long) authMetrics.personalSpaceProvisioningFailedCount(),
          e);
    }
  }
}
