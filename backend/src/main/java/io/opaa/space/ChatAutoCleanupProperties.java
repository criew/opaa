package io.opaa.space;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The installation-wide periods of the automatic chat cleanup a space can switch on (#1923). Only
 * the operator sets them; a value below its floor stops the start, so no configuration can turn the
 * cleanup into a short-notice deletion.
 *
 * @param archiveAfterDays days without chat activity after which a chat that is not pinned is
 *     archived; default 90, at least {@value #MIN_ARCHIVE_AFTER_DAYS}
 * @param deleteAfterDays days in the chat archive after which a chat is deleted; default 365, at
 *     least {@value #MIN_DELETE_AFTER_DAYS}
 */
@ConfigurationProperties(prefix = "opaa.chat.auto-cleanup")
public record ChatAutoCleanupProperties(
    @DefaultValue("90") int archiveAfterDays, @DefaultValue("365") int deleteAfterDays) {

  public static final int MIN_ARCHIVE_AFTER_DAYS = 90;
  public static final int MIN_DELETE_AFTER_DAYS = 30;

  public ChatAutoCleanupProperties {
    if (archiveAfterDays < MIN_ARCHIVE_AFTER_DAYS) {
      throw new IllegalArgumentException(
          "opaa.chat.auto-cleanup.archive-after-days must be at least "
              + MIN_ARCHIVE_AFTER_DAYS
              + ", got "
              + archiveAfterDays);
    }
    if (deleteAfterDays < MIN_DELETE_AFTER_DAYS) {
      throw new IllegalArgumentException(
          "opaa.chat.auto-cleanup.delete-after-days must be at least "
              + MIN_DELETE_AFTER_DAYS
              + ", got "
              + deleteAfterDays);
    }
  }

  /** Chats whose last activity lies before this instant are due for archiving at {@code now}. */
  public Instant archiveCutoff(Instant now) {
    return now.minus(Duration.ofDays(archiveAfterDays));
  }

  /** Chats archived before this instant are due for deletion at {@code now}. */
  public Instant deleteCutoff(Instant now) {
    return now.minus(Duration.ofDays(deleteAfterDays));
  }

  /**
   * When a chat archived at {@code archivedAt} in a space whose cleanup was switched on at {@code
   * enabledAt} is deleted: the period starts at the later of the two, never before switching on.
   * {@code null} if the chat is not archived or the cleanup is off.
   */
  public Instant deletionDueAt(Instant archivedAt, Instant enabledAt) {
    if (archivedAt == null || enabledAt == null) {
      return null;
    }
    Instant start = archivedAt.isAfter(enabledAt) ? archivedAt : enabledAt;
    return start.plus(Duration.ofDays(deleteAfterDays));
  }
}
