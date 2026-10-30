package io.opaa.api.types;

/**
 * The closed vocabulary of {@code Notification#getType()} (backend module). This enum is the sole
 * write guard since migration 066 dropped the database check constraint {@code
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
   * The external-access channel exceeded the mass-retrieval threshold
   * (docs/features/external-access.md - "Kontingente und der Abflussalarm"). Sent to every system
   * administrator of the organization, exactly once per cooldown, and never written to the audit
   * trail: the alert is a security event, only the suspension that may follow it is a state change.
   */
  EXTERNAL_ACCESS_MASS_RETRIEVAL,

  /**
   * The account was taken into an internal group (ADR-0036 Entscheidung 4). Sent to the person
   * concerned, in the application and never by mail (Personalrat A4): rights may grow to them
   * through the group, and they are to learn that they did.
   */
  GROUP_MEMBER_ADDED,

  /**
   * The account was removed from an internal group. The counterpart of {@link #GROUP_MEMBER_ADDED},
   * and the more important half of it: a read right can otherwise end "immediately" without the
   * person learning that it did, or through whom.
   */
  GROUP_MEMBER_REMOVED,

  /**
   * The provider rejected the secret of the person's connected account (ADR-0048): their private
   * library is not updated until they connect the account anew. Sent to that person only.
   */
  CONNECTION_EXPIRED,

  /**
   * The system administration ended the person's connected account - a changed, shut down or
   * deleted profile (ADR-0041, Entscheidung 4). Sent to that person only; its object is the
   * profile.
   */
  CONNECTION_ENDED,

  /**
   * A change of its profile released the person's private library from it (ADR-0049, Entscheidung
   * 3): the library rests, keeps its content and runs again once she connects it through another
   * profile. Sent to its owner only; its object is the library.
   */
  PRIVATE_LIBRARY_RELEASED,

  /**
   * The system administration changed a profile default only the profile sets, such as Google
   * Drive's imitated account: the run state of every library on the profile is discarded and its
   * next run is a full one. Sent to each library's managers.
   */
  SOURCE_FULL_SYNC_FORCED,

  /**
   * The provider named an end of the person's OAuth consent, and it is 14 days away or less. Sent
   * once per end to that person only; its object is the profile.
   */
  CONNECTION_EXPIRING,

  /**
   * The client secret of a connection profile reaches its expiry date within 14 days. Sent once per
   * date to every system administrator; its object is the profile.
   */
  CONNECTION_PROFILE_SECRET_EXPIRING,

  /**
   * The provider named an end of a library's own OAuth consent ("Quelle verbinden"), 14 days away
   * or less. Sent once per end to those responsible for it; its object is the library.
   */
  SOURCE_CONNECTION_EXPIRING,

  /**
   * The provider no longer takes a library's own OAuth consent; the library is not updated until
   * its source is connected anew. Sent to those responsible; its object is the library.
   */
  SOURCE_CONNECTION_EXPIRED,

  /**
   * The system administration ended a library's own OAuth consent - a changed, shut down or deleted
   * profile. Sent to those responsible; its object is the library.
   */
  SOURCE_CONNECTION_ENDED,

  /**
   * An export of the person's self-disclosure began - sent at the start, so it reaches her also
   * when the export aborts. Sent to that person only, without content and without a link.
   */
  SELF_DISCLOSURE_STARTED,

  /**
   * The system administration or the roles claim of an identity provider asked for the AUDITOR role
   * for an account; it takes effect once an auditor confirms. Sent to every auditor of the
   * organization; its object is the account.
   */
  AUDITOR_ROLE_REQUESTED,

  /**
   * An account received the AUDITOR role - confirmed, or as the first one of the organization. Sent
   * to every other auditor of the organization; its object is the account.
   */
  AUDITOR_ROLE_GRANTED,

  /**
   * An account left the AUDITOR role - by a role change, a provider's claim or its deletion. Sent
   * to every remaining auditor of the organization; its object is the account.
   */
  AUDITOR_ROLE_REVOKED,

  /**
   * The system administration changed the SMTP settings or a mail template. Sent to every auditor
   * of the installation; no object.
   */
  MAIL_CONFIGURATION_CHANGED,

  /**
   * The system administration created an identity provider or changed its trust settings (issuer,
   * client id, signing keys, claims, role mapping). Sent to every auditor of the installation; its
   * object is the provider.
   */
  IDENTITY_PROVIDER_TRUST_CHANGED
}
