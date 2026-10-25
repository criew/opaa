package io.opaa.chat;

import java.time.Duration;

/**
 * The deletion of the chats of a local account locked by the administration, as {@link
 * LockedAccountChatCleanupSettings} holds it. The period has a floor no setting undercuts, so an
 * erroneous lock always has weeks to be noticed.
 *
 * @param enabled whether the nightly run deletes; off keeps every chat and lets every period start
 *     anew once switched on again
 * @param deleteAfterDays days between the lock by the administration and the deletion of the
 *     account's chats, {@value #MIN_DELETE_AFTER_DAYS} to {@value #MAX_DELETE_AFTER_DAYS}
 */
public record LockedAccountChatCleanupPolicy(boolean enabled, int deleteAfterDays) {

  public static final int MIN_DELETE_AFTER_DAYS = 90;
  public static final int MAX_DELETE_AFTER_DAYS = 3650;

  /** The policy in force while the administration set none. */
  public static final LockedAccountChatCleanupPolicy FACTORY_DEFAULTS =
      new LockedAccountChatCleanupPolicy(true, 365);

  public LockedAccountChatCleanupPolicy {
    if (deleteAfterDays < MIN_DELETE_AFTER_DAYS || deleteAfterDays > MAX_DELETE_AFTER_DAYS) {
      throw new IllegalArgumentException(
          "deleteAfterDays must lie between "
              + MIN_DELETE_AFTER_DAYS
              + " and "
              + MAX_DELETE_AFTER_DAYS
              + ", got "
              + deleteAfterDays);
    }
  }

  public Duration period() {
    return Duration.ofDays(deleteAfterDays);
  }
}
