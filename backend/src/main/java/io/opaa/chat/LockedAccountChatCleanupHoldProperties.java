package io.opaa.chat;

import io.opaa.compliance.DataImpact;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The bound of a hold of the deletion of a locked account's chats, set by the operation and not by
 * the administration that requests and approves the holds - as the bound of the anlassbezogene
 * Klärung (ADR-0060, Entscheidung 2).
 *
 * @param holdMaxDays how long an approved hold runs at most before it ends by itself, in days;
 *     default 180, from {@value #MIN_HOLD_MAX_DAYS} to {@value #MAX_HOLD_MAX_DAYS}. Running on
 *     needs a new request and a new approval. A changed value acts on holds approved from then on.
 */
@ConfigurationProperties(prefix = "opaa.chat.locked-account-cleanup")
public record LockedAccountChatCleanupHoldProperties(
    @DefaultValue("180") @DataImpact int holdMaxDays) {

  public static final int MIN_HOLD_MAX_DAYS = 7;
  public static final int MAX_HOLD_MAX_DAYS = 730;

  public LockedAccountChatCleanupHoldProperties {
    if (holdMaxDays < MIN_HOLD_MAX_DAYS || holdMaxDays > MAX_HOLD_MAX_DAYS) {
      throw new IllegalArgumentException(
          "opaa.chat.locked-account-cleanup.hold-max-days must lie between "
              + MIN_HOLD_MAX_DAYS
              + " and "
              + MAX_HOLD_MAX_DAYS
              + ", got "
              + holdMaxDays);
    }
  }

  public Duration holdMaxDuration() {
    return Duration.ofDays(holdMaxDays);
  }
}
