package io.opaa.auth.account;

import io.opaa.api.types.ProviderType;
import io.opaa.auth.User;
import io.opaa.auth.local.LocalUserOverview;
import io.opaa.auth.oidc.OidcProvider;
import java.util.Objects;

/**
 * One account as the administration sees it (#1601): a local account with its {@link
 * LocalUserOverview} (state and activity class), or an identity-provider account with the provider
 * row it belongs to - {@code null} when that row has been deleted since, the account itself remains
 * (ADR-0025). Never both.
 *
 * @param roleManagedByProvider an enabled provider with a roles claim is authoritative for the
 *     account's role (ADR-0025, Entscheidung 4) - the condition under which {@code
 *     UserService#updateRole} refuses a manual change
 */
public record AccountOverview(
    User user, OidcProvider provider, LocalUserOverview local, boolean roleManagedByProvider) {

  public AccountOverview {
    Objects.requireNonNull(user, "user");
    if (local != null && provider != null) {
      throw new IllegalArgumentException("an account is local or belongs to a provider, not both");
    }
    if (local != null && local.user() != user) {
      throw new IllegalArgumentException("the local overview must describe the same user");
    }
  }

  static AccountOverview local(LocalUserOverview local) {
    return new AccountOverview(local.user(), null, local, false);
  }

  static AccountOverview ofProvider(User user, OidcProvider provider) {
    boolean managed =
        provider != null && provider.isEnabled() && provider.getClaimMapping().rolesClaim() != null;
    return new AccountOverview(user, provider, null, managed);
  }

  public boolean isLocal() {
    return local != null;
  }

  public ProviderType providerType() {
    return isLocal() ? ProviderType.LOCAL : ProviderType.OIDC;
  }
}
