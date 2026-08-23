package io.opaa.audit;

/**
 * The closed list of events the first protocol stage records
 * (docs/features/security-and-compliance.md#die-ereignisse-der-ersten-stufe, decision #355). "Was
 * hier nicht steht, wird in der ersten Stufe nicht geschrieben" - the list is deliberately closed,
 * not a category with a free-text detail: {@link AuditLogEntry#getEventType()} is this enum, not a
 * {@code String}, and the database check constraint {@code chk_audit_log_event_type} (migration
 * 017) enforces the same closed set independently. Adding a value here requires a migration
 * updating that constraint in lockstep.
 *
 * <p>#391 only builds the store; no service emits any of these yet. Emission is wired up service by
 * service in later issues, against this exact list.
 */
public enum AuditEventType {

  // Rechte an Assets
  /** Includes co-shares originating from the share chain ("Mitfreigaben aus der Freigabekette"). */
  ASSET_GRANT_GRANTED,
  ASSET_GRANT_CHANGED,
  ASSET_GRANT_REVOKED,
  /** A time-limited grant expiring is itself an event, the moment it takes effect. */
  ASSET_GRANT_EXPIRED,
  /** Change of an asset's visibility or listedness (visibility, listed). */
  ASSET_VISIBILITY_CHANGED,
  /** Grants suspended by a subsequently lowered connector share ceiling. */
  ASSET_GRANT_SUSPENDED,

  // Spaces, Bibliotheken und Gruppen
  SPACE_CREATED,
  SPACE_CHANGED,
  SPACE_DELETED,
  /**
   * #543: a space stopped instead of deleted, chiefly because it still contains a chat authored by
   * someone other than the space owner - fk_chats_space_organization (ON DELETE RESTRICT, migration
   * 032, composite as of migration 047) makes such a space permanently undeletable otherwise.
   */
  SPACE_ARCHIVED,
  LIBRARY_CREATED,
  LIBRARY_CHANGED,
  LIBRARY_DELETED,
  /**
   * A library's source configuration (sourcePath/sourceUrl/sourceProxy/sourceCredentials/
   * sourceInsecureSsl) changed - distinct from {@link #LIBRARY_CHANGED} (name/description) and
   * {@link #ASSET_VISIBILITY_CHANGED} (visibility/listed), neither of which fires for a source
   * configuration edit alone (#545). Only which fields changed is recorded, never their values - a
   * stricter version of the "no value, only which field" rule {@link #LIBRARY_CHANGED} already
   * applies to description, since sourceCredentials must never appear in the log at all.
   */
  LIBRARY_SOURCE_UPDATED,
  GROUP_CREATED,
  GROUP_CHANGED,
  /** Also covers a group's dissolution ("Auflösung einer Gruppe"). */
  GROUP_DELETED,
  /**
   * Also covers admitting an external person into a space with shared content, with the explicit
   * confirmation the specification requires carried in the entry's {@code reason}/{@code after}.
   */
  SPACE_MEMBER_ADDED,
  SPACE_MEMBER_ROLE_CHANGED,
  SPACE_MEMBER_REMOVED,
  GROUP_MEMBER_ADDED,
  GROUP_MEMBER_REMOVED,
  /**
   * A library associated with (made available in) a space (#203/#706 review) - recorded for every
   * association, not only the mixed-audience case the name might suggest; whether the audience was
   * mixed is a separate fact (the owner notification, {@code
   * SpaceAssetAssociationService#notifyOwnerIfMixedAudience}), not part of this event's own
   * condition. See {@link #LIBRARY_DETACHED_FROM_SPACE} for the reverse operation - the two are
   * always distinguishable by type, never inferred from before/after payload shape.
   */
  LIBRARY_SHARED_TO_SPACE,
  /**
   * The reverse of {@link #LIBRARY_SHARED_TO_SPACE} - a library detached from a space (#706
   * review).
   */
  LIBRARY_DETACHED_FROM_SPACE,
  ASSET_OWNER_CHANGED,
  /** Taking over an asset left without a responsible owner. */
  ASSET_OWNERSHIP_CLAIMED,
  /** An asset entering the "Nachfolge offen" state. */
  ASSET_SUCCESSION_OPENED,

  // Konten, Rollen und Verzeichnisabgleich
  SYSTEM_ADMIN_ROLE_GRANTED,
  SYSTEM_ADMIN_ROLE_REVOKED,
  /**
   * #393 code review, finding 1: distinct from {@link #SYSTEM_ADMIN_ROLE_GRANTED}/{@link
   * #SYSTEM_ADMIN_ROLE_REVOKED} - granting the AUDITOR role is not an administrative privilege
   * change and must never be recorded as one (a prior version of {@code UserService#updateRole} did
   * exactly that, mislabelling every AUDITOR grant as a SYSTEM_ADMIN_ROLE_REVOKED). Migration 022
   * widens {@code chk_audit_log_event_type} to include these two values.
   */
  AUDITOR_ROLE_GRANTED,
  AUDITOR_ROLE_REVOKED,
  ACCOUNT_DEACTIVATED,
  ACCOUNT_REAUTHENTICATION_FORCED,
  API_TOKEN_ISSUED,
  API_TOKEN_REVOKED,
  /** One entry per effected change from a directory sync run, linked via {@code correlationRef}. */
  DIRECTORY_SYNC_CHANGE_APPLIED,
  /** The header entry of a directory sync run, with its outcome. */
  DIRECTORY_SYNC_RUN_COMPLETED,

  // Systemeinstellungen
  GOVERNANCE_SETTINGS_CHANGED,
  /** Includes enabling the network address field, per the specification's explicit requirement. */
  AUDIT_LOG_CONFIGURATION_CHANGED,
  /** Covers both model defaults and the approval of external models. */
  MODEL_POLICY_CHANGED,
  CONNECTOR_LIBRARY_SHARE_LIMIT_CHANGED,
  /**
   * A change to the operator's branding - product name, claim, logo, accent colour or default
   * colour scheme (#582, docs/design/guidelines.md#7). Deliberately not folded into {@link
   * #GOVERNANCE_SETTINGS_CHANGED}: branding decides what every user sees on every page, which is
   * exactly the kind of change an auditor wants to find by its own name rather than inside a
   * catch-all governance bucket. Migration 042 widens {@code chk_audit_log_event_type} to include
   * it.
   */
  BRANDING_SETTINGS_CHANGED,
  /**
   * A managed chat model (Stufe 1, #756, {@code io.opaa.llm.LlmModel}) was created. Migration 059
   * widens {@code chk_audit_log_event_type} to include this and the three sibling values below.
   */
  LLM_MODEL_CREATED,
  /** A managed chat model's editable fields (display name, base URL, model id, ...) changed. */
  LLM_MODEL_CHANGED,
  /** A managed chat model was deleted. */
  LLM_MODEL_DELETED,
  /**
   * A managed chat model became the one systemwide active model - distinct from {@link
   * #LLM_MODEL_CHANGED} for the same reason {@link #SPACE_MEMBER_ROLE_CHANGED} is distinct from
   * {@link #SPACE_MEMBER_ADDED}: an auditor asking "when did the active model change" should not
   * have to inspect before/after payloads to tell activation apart from an ordinary field edit.
   */
  LLM_MODEL_ACTIVATED,
  /**
   * A managed chat model stopped being the systemwide active one because a different model was
   * activated in its place (#757 review of #763) - without this, "wann hörte Modell X auf, aktiv zu
   * sein" was only indirectly readable from the {@link #LLM_MODEL_ACTIVATED} event of whatever
   * model replaced it. Migration 061 widens {@code chk_audit_log_event_type} to include this value.
   */
  LLM_MODEL_DEACTIVATED,

  // Zugriff auf die Protokolldaten selbst
  /** Any read, evaluation or export of audit data, including rejected attempts (see outcome). */
  AUDIT_LOG_ACCESSED
}
