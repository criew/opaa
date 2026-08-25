package io.opaa.api.types;

/**
 * The closed vocabulary of {@link Notification#getType()}. This enum is the sole write guard since
 * migration 066 (#862) dropped the database check constraint {@code chk_notifications_type} that
 * used to enforce the same closed set independently - adding a value here no longer requires a
 * migration.
 */
public enum NotificationType {
  /**
   * A library was associated into a space whose members do not all already have read access to it
   * (#203, docs/features/spaces-and-assets.md#assets-in-einen-space-assoziieren - "Benachrichtigung
   * statt Zustimmung"). Sent to the library's owner - every member of the owning group, if
   * group-owned.
   */
  LIBRARY_ASSOCIATED_TO_MIXED_SPACE
}
