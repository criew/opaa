package io.opaa.api.types;

/**
 * The closed list of events the first protocol stage records
 * (docs/features/security-and-compliance.md#die-ereignisse-der-ersten-stufe). "Was hier nicht
 * steht, wird in der ersten Stufe nicht geschrieben" - the list is deliberately closed, not a
 * category with a free-text detail: {@link AuditLogEntry#getEventType()} is this enum, not a {@code
 * String}. This enum is the sole write guard; there is no longer a matching database check
 * constraint, so adding a value here does not require a migration.
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
   * A space stopped instead of deleted, chiefly because it still contains a chat authored by
   * someone other than the space owner - an {@code ON DELETE RESTRICT} foreign key makes such a
   * space permanently undeletable otherwise.
   */
  SPACE_ARCHIVED,
  LIBRARY_CREATED,
  LIBRARY_CHANGED,
  LIBRARY_DELETED,
  /**
   * A library's source configuration (sourcePath/sourceUrl/sourceProxy/sourceCredentials/
   * sourceInsecureSsl) changed - distinct from {@link #LIBRARY_CHANGED} (name/description) and
   * {@link #ASSET_VISIBILITY_CHANGED} (visibility/listed), neither of which fires for a source
   * configuration edit alone. Only which fields changed is recorded, never their values -
   * sourceCredentials must never appear in the log at all.
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
   * A library associated with (made available in) a space - recorded for every association, not
   * only the mixed-audience case the name might suggest; whether the audience was mixed is a
   * separate fact (the owner notification), not part of this event's own condition. See {@link
   * #LIBRARY_DETACHED_FROM_SPACE} for the reverse operation - the two are always distinguishable by
   * type, never inferred from before/after payload shape.
   */
  LIBRARY_SHARED_TO_SPACE,
  /** The reverse of {@link #LIBRARY_SHARED_TO_SPACE} - a library detached from a space. */
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
   * Distinct from {@link #SYSTEM_ADMIN_ROLE_GRANTED}/{@link #SYSTEM_ADMIN_ROLE_REVOKED} - granting
   * the AUDITOR role is not an administrative privilege change and must never be recorded as one.
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
   * A selective re-index of the chunk bestand by ingestion pipeline version was triggered
   * (docs/features/ingestion-pipelines.md, cross-cutting rule (d)). Recorded per triggering call,
   * not per document: the call is the administrative decision, the documents are its effect.
   */
  INDEXING_PIPELINE_REINDEX_TRIGGERED,
  /**
   * A deterministic core-metadata backfill over a library's Altbestand was triggered
   * (docs/features/metadata-schema.md, "Deterministischer Bestandslauf"). Recorded per triggering
   * call, not per document, mirroring {@link #INDEXING_PIPELINE_REINDEX_TRIGGERED}.
   */
  INDEXING_METADATA_BACKFILL_TRIGGERED,
  /**
   * A change to the operator's branding - product name, claim, logo, accent colour or default
   * colour scheme (docs/design/guidelines.md#7). Deliberately not folded into {@link
   * #GOVERNANCE_SETTINGS_CHANGED}: branding decides what every user sees on every page, which is
   * exactly the kind of change an auditor wants to find by its own name rather than inside a
   * catch-all governance bucket.
   */
  BRANDING_SETTINGS_CHANGED,
  /** A managed chat model ({@code io.opaa.llm.LlmModel}) was created. */
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
   * activated in its place - without this, "wann hörte Modell X auf, aktiv zu sein" was only
   * indirectly readable from the {@link #LLM_MODEL_ACTIVATED} event of whatever model replaced it.
   */
  LLM_MODEL_DEACTIVATED,

  // Diagnose in fremdem Rechtekontext ("Sicht als", #1052)
  /**
   * The separately granted "Sicht als" befugnis (scope plus expiry) was given to a person. Never
   * implied by {@link #SYSTEM_ADMIN_ROLE_GRANTED} - the befugnis is not derived from any role.
   */
  DIAGNOSTIC_IMPERSONATION_GRANTED,
  /** A "Sicht als" befugnis was revoked before its own expiry. */
  DIAGNOSTIC_IMPERSONATION_REVOKED,
  /** The retention period of the diagnostic context protocol changed (12 months by default). */
  DIAGNOSTIC_CONTEXT_RETENTION_CHANGED,
  /**
   * A library's diagnosesperre was set or lifted by the responsible owner. Distinct from {@link
   * #LIBRARY_CHANGED}: this flag decides whether a foreign rights context can see the library at
   * all, and an auditor must be able to find that change by its own name.
   */
  LIBRARY_DIAGNOSTICS_LOCK_CHANGED,

  // Zugriff auf die Protokolldaten selbst
  /** Any read, evaluation or export of audit data, including rejected attempts (see outcome). */
  AUDIT_LOG_ACCESSED
}
