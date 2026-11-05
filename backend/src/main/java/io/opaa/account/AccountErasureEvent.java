package io.opaa.account;

import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import java.time.Instant;
import java.util.Objects;

/**
 * Published inside the transaction of an account's erasure ({@link AccountErasureService}), after
 * its checks and before the {@code users} row loses its personal reference. Each listener removes
 * what the person holds in its own package; the row stays, so no foreign key forces an order beyond
 * the one below. A listener may still refuse with a {@code ConflictException}, which rolls back the
 * whole erasure - the chats refuse while their deletion is held or a hold is requested.
 *
 * <p>A plain {@code @EventListener} runs in the publisher's transaction: everything a listener
 * writes commits with the erasure or not at all.
 */
public record AccountErasureEvent(CurrentUser actor, User user, Instant at) {

  /** The diagnostic impersonation grants the account issued are revoked first. */
  public static final int IMPERSONATION_GRANTS_ORDER = 100;

  /** The chats refuse while their deletion is held, else go - before the personal space. */
  public static final int CHATS_ORDER = 200;

  /** The personal spaces go once their chats are gone. */
  public static final int PERSONAL_SPACES_ORDER = 300;

  /** Everything else of the person, in no particular order. */
  public static final int PERSONAL_DATA_ORDER = 400;

  public AccountErasureEvent {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(at, "at");
  }
}
