package io.opaa.auth;

import io.opaa.auth.oidc.OidcProvider;
import java.util.List;

/**
 * Published by {@link UserService} once per provisioning of an account, after the {@code users} row
 * is committed and the token's roles claim has been applied.
 *
 * <p>Listeners run synchronously in the publishing thread through a plain {@code @EventListener},
 * not {@code @TransactionalEventListener}: {@link UserService#findOrCreateUser} deliberately has no
 * transaction to hang a phase on (a transactional listener would be skipped outright), and the
 * request must not be authorized before the memberships a listener writes are in place. Each
 * listener brings its own transaction; an exception from one fails the publishing request and stops
 * the later ones, so the order below is a contract, not a preference.
 */
public record UserProvisionedEvent(
    User user, boolean createdHere, OidcProvider provider, List<String> tokenGroups) {

  /** The personal space is provisioned before any rights-affecting listener of this event. */
  public static final int PERSONAL_SPACE_ORDER = 100;

  /** The token's group memberships are written after the personal space. */
  public static final int TOKEN_GROUPS_ORDER = 200;

  /**
   * {@code provider} and {@code tokenGroups} are present together or not at all - a listener that
   * sees one may rely on the other, and {@link #hasTokenGroups()} decides for both.
   */
  public UserProvisionedEvent {
    if ((provider == null) != (tokenGroups == null)) {
      throw new IllegalArgumentException(
          "provider and tokenGroups must be set together or left out together");
    }
    tokenGroups = tokenGroups == null ? null : List.copyOf(tokenGroups);
  }

  /**
   * @param createdHere {@code true} only if this sign-in's own insert created {@code user}'s row -
   *     {@code false} for a returning account and for the loser of a concurrent first sign-in.
   */
  public static UserProvisionedEvent withoutTokenGroups(User user, boolean createdHere) {
    return new UserProvisionedEvent(user, createdHere, null, null);
  }

  /** {@code provider}'s groups claim named exactly {@code tokenGroups} for {@code user}. */
  public static UserProvisionedEvent withTokenGroups(
      User user, boolean createdHere, OidcProvider provider, List<String> tokenGroups) {
    return new UserProvisionedEvent(user, createdHere, provider, tokenGroups);
  }

  /**
   * Whether a provider's groups claim is authoritative for this sign-in; {@link #provider()} and
   * {@link #tokenGroups()} are {@code null} otherwise.
   */
  public boolean hasTokenGroups() {
    return provider != null;
  }
}
