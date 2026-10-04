package io.opaa.api.types;

/**
 * The closed vocabulary of {@code Notification#getType()} (backend module). This enum is the sole
 * write guard since migration 066 (#862) dropped the database check constraint {@code
 * chk_notifications_type} that used to enforce the same closed set independently - adding a value
 * here no longer requires a migration.
 */
public enum NotificationType {
  /**
   * An asset of any type was associated into a space whose members do not all already have read
   * access to it (docs/features/spaces-and-assets.md#assets-in-einen-space-assoziieren -
   * "Benachrichtigung statt Zustimmung"). Sent to the asset's owner - every member of the owning
   * group, if group-owned - once per owner and request, naming every such asset of theirs.
   */
  ASSET_ASSOCIATED_TO_MIXED_SPACE,

  /**
   * The external-access channel exceeded the mass-retrieval threshold (#1720,
   * docs/features/external-access.md - "Kontingente und der Abflussalarm"). Sent to every system
   * administrator of the organization, exactly once per cooldown, and never written to the audit
   * trail: the alert is a security event, only the suspension that may follow it is a state change.
   */
  EXTERNAL_ACCESS_MASS_RETRIEVAL,

  /**
   * The account was taken into an internal group (#1814, ADR-0036 Entscheidung 4). Sent to the
   * person concerned, in the application and never by mail (Personalrat A4): rights may grow to
   * them through the group, and they are to learn that they did.
   */
  GROUP_MEMBER_ADDED,

  /**
   * The account was removed from an internal group (#1814). The counterpart of {@link
   * #GROUP_MEMBER_ADDED}, and the more important half of it: a read right can otherwise end
   * "immediately" without the person learning that it did, or through whom.
   */
  GROUP_MEMBER_REMOVED,

  /**
   * The provider rejected the secret of the person's connected account (ADR-0041): their private
   * library is not updated until they connect the account anew. Sent to that person only.
   */
  CONNECTION_EXPIRED,

  /**
   * A person asked for a connection profile ("Zugangswunsch"). Sent to every system administrator
   * of the organization; names type and server address, never the person's reason.
   */
  CONNECTION_PROFILE_REQUESTED,

  /**
   * The system administration marked a connection profile request done or declined. Sent to the
   * person who made it, with the administration's answer if it gave one.
   */
  CONNECTION_PROFILE_REQUEST_RESOLVED,

  /**
   * The system administration ended the person's connected account - a changed, shut down or
   * deleted profile (ADR-0041). Sent to that person only; its object is the profile.
   */
  CONNECTION_ENDED,

  /**
   * A change of its profile released the person's private library from it (ADR-0041): the library
   * rests, keeps its content and runs again once she connects it through another profile. Sent to
   * its owner only; its object is the library.
   */
  PRIVATE_LIBRARY_RELEASED,

  /**
   * The system administration changed a profile default only the profile sets, such as Google
   * Drive's imitated account: the run state of every library on the profile is discarded and its
   * next run is a full one. Sent to each library's managers.
   */
  SOURCE_FULL_SYNC_FORCED
}
