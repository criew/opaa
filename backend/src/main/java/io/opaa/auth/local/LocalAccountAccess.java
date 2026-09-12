package io.opaa.auth.local;

import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.LockReason;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.oidc.OidcProviderRegistry;
import java.time.Instant;

/**
 * The one formulation of who may use the local sign-in right now (ADR-0033, Entscheidungen 3 and
 * 4), shared by the login, the refresh rotation and the token validator: the account must be {@code
 * ACTIVE} by {@link LocalCredentials#isLoginCapable} - never by {@code locked_at} alone, which
 * outlives an expired lockout - and while the management is switched off only a local {@code
 * SYSTEM_ADMIN} passes.
 */
final class LocalAccountAccess {

  private LocalAccountAccess() {}

  static boolean isLoginCapable(LocalCredentials credentials, Instant now) {
    return credentials.isLoginCapable(now);
  }

  /**
   * Whether a single-use link of the account may still be redeemed (ADR-0033, Entscheidungen 9 and
   * 11): not after a lock by the administration or for inactivity and not after the expiry date - a
   * failed-login lockout is the one state a redeemed reset link lifts, and an open invitation is
   * what a set-password link exists for.
   */
  static boolean mayRedeemLink(LocalCredentials credentials, Instant now) {
    LocalAccountState state = credentials.state(now);
    if (state == LocalAccountState.EXPIRED) {
      return false;
    }
    return state != LocalAccountState.LOCKED
        || credentials.getLockedReason() == LockReason.FAILED_LOGINS;
  }

  static boolean passesManagementSwitch(OidcProviderRegistry registry, User user) {
    return registry.localAccountsEnabled() || user.getSystemRole() == SystemRole.SYSTEM_ADMIN;
  }
}
