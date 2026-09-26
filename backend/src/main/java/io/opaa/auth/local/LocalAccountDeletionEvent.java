package io.opaa.auth.local;

import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import java.util.Objects;

/**
 * Published inside the transaction of a local account's deletion, after the deletion checks and
 * before the {@code users} row goes. Each listener removes or ends what the account holds in its
 * own package, so the deletion knows none of them.
 *
 * <p>A plain {@code @EventListener} runs in the publisher's transaction: everything a listener
 * writes commits with the deletion or not at all, and an exception from a listener fails the
 * deletion and stops the later ones - the order below is a contract.
 */
public record LocalAccountDeletionEvent(CurrentUser actor, User user) {

  /** The diagnostic impersonation grants the account issued are revoked first. */
  public static final int IMPERSONATION_GRANTS_ORDER = 100;

  /** The personal spaces go after the grants, audited as a space deletion. */
  public static final int PERSONAL_SPACES_ORDER = 200;

  public LocalAccountDeletionEvent {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(user, "user");
  }
}
