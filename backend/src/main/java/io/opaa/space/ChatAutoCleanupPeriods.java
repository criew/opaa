package io.opaa.space;

import java.time.Duration;
import java.time.Instant;

/**
 * The installation-wide periods of the automatic chat cleanup a space can switch on, as {@link
 * ChatAutoCleanupSettings} holds them. Every value lies within its floor and ceiling, so no setting
 * can turn the cleanup into a short-notice deletion.
 *
 * @param archiveAfterDays days without chat activity after which a chat that is not pinned is
 *     archived, {@value #MIN_ARCHIVE_AFTER_DAYS} to {@value #MAX_DAYS}
 * @param deleteAfterDays days in the chat archive after which a chat is deleted, {@value
 *     #MIN_DELETE_AFTER_DAYS} to {@value #MAX_DAYS}
 */
public record ChatAutoCleanupPeriods(int archiveAfterDays, int deleteAfterDays) {

  public static final int MIN_ARCHIVE_AFTER_DAYS = 90;
  public static final int MIN_DELETE_AFTER_DAYS = 30;
  public static final int MAX_DAYS = 3650;

  /** The periods in force while the administration set none. */
  public static final ChatAutoCleanupPeriods FACTORY_DEFAULTS = new ChatAutoCleanupPeriods(90, 365);

  public ChatAutoCleanupPeriods {
    if (archiveAfterDays < MIN_ARCHIVE_AFTER_DAYS || archiveAfterDays > MAX_DAYS) {
      throw new IllegalArgumentException(
          "archiveAfterDays must lie between "
              + MIN_ARCHIVE_AFTER_DAYS
              + " and "
              + MAX_DAYS
              + ", got "
              + archiveAfterDays);
    }
    if (deleteAfterDays < MIN_DELETE_AFTER_DAYS || deleteAfterDays > MAX_DAYS) {
      throw new IllegalArgumentException(
          "deleteAfterDays must lie between "
              + MIN_DELETE_AFTER_DAYS
              + " and "
              + MAX_DAYS
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
