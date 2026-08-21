package io.opaa.notification;

/**
 * The closed vocabulary of {@link Notification#getType()}, mirrored by the database check
 * constraint {@code chk_notifications_type} (migration 052) - keep both in sync, the same
 * discipline {@code io.opaa.audit.AuditEventType} already applies to {@code
 * chk_audit_log_event_type}.
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
