package io.opaa.chat;

import io.opaa.compliance.DataImpact;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The deletion of the chats of a local account locked by the administration, set by the operator
 * only. The period has a floor no configuration undercuts: a smaller value stops the start, so an
 * erroneous lock always has weeks to be noticed.
 *
 * @param enabled whether the nightly run deletes; default on, off keeps every chat and lets every
 *     period start anew once switched on again
 * @param deleteAfterDays days between the lock by the administration and the deletion of the
 *     account's chats; default 365, at least {@value #MIN_DELETE_AFTER_DAYS}
 */
@ConfigurationProperties(prefix = "opaa.chat.locked-account-cleanup")
public record LockedAccountChatCleanupProperties(
    @DefaultValue("true") @DataImpact boolean enabled,
    @DefaultValue("365") @DataImpact int deleteAfterDays) {

  public static final int MIN_DELETE_AFTER_DAYS = 90;

  public LockedAccountChatCleanupProperties {
    if (deleteAfterDays < MIN_DELETE_AFTER_DAYS) {
      throw new IllegalArgumentException(
          "opaa.chat.locked-account-cleanup.delete-after-days must be at least "
              + MIN_DELETE_AFTER_DAYS
              + ", got "
              + deleteAfterDays);
    }
  }

  public Duration period() {
    return Duration.ofDays(deleteAfterDays);
  }
}
