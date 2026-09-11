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
  /**
   * A provider's token withdrew {@code SYSTEM_ADMIN} from the last system administrator and the
   * withdrawal was refused (ADR-0025, Entscheidung 4): the installation must never be left without
   * one. Written under the identity-provider system actor.
   */
  SYSTEM_ADMIN_ROLE_REVOCATION_REFUSED,
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
   * A Nachlauf re-embedding a library's chunks under the current Kontextpraefix was triggered
   * (docs/features/metadata-schema.md, "Nachlauf im Betrieb"). Recorded per triggering call, not
   * per document, mirroring {@link #INDEXING_METADATA_BACKFILL_TRIGGERED}.
   */
  INDEXING_CONTEXT_PREFIX_RERUN_TRIGGERED,
  /**
   * Explicitly named orphaned originals - stored originals no document row of the library points to
   * - were removed from the upload storage (ADR-0030, "Konsequenzen"). Recorded per triggering
   * call, not per original, mirroring {@link #INDEXING_PIPELINE_REINDEX_TRIGGERED}; the payload
   * names every locator that went. The report step that precedes it changes nothing and is not
   * recorded.
   */
  UPLOAD_ORPHAN_ORIGINALS_DELETED,
  /**
   * A person set, changed or removed a core metadata value of a document by hand
   * (docs/features/metadata-schema.md, "Manuelle Setzungen sind protokollpflichtig"). One entry per
   * document and field, carrying the old and the new value - also for every document of a
   * Sammelzuweisung, whose entries share a {@code correlationRef}. The object is the library the
   * document belongs to; the document itself is named in the payload, so every manual value of a
   * library can be read back through the object access path after a restore.
   */
  DOCUMENT_METADATA_CHANGED,
  /**
   * A change to the operator's branding - product name, claim, logo, accent colour or default
   * colour scheme (docs/design/guidelines.md#7). Deliberately not folded into {@link
   * #GOVERNANCE_SETTINGS_CHANGED}: branding decides what every user sees on every page, which is
   * exactly the kind of change an auditor wants to find by its own name rather than inside a
   * catch-all governance bucket.
   */
  BRANDING_SETTINGS_CHANGED,
  /** An identity provider ({@code io.opaa.auth.oidc.OidcProvider}, ADR-0025) was created. */
  OIDC_PROVIDER_CREATED,
  /**
   * An identity provider's editable fields changed - including its default-provider flag, its sort
   * order and, most consequentially, its JWK set address (the trust anchor of every account of that
   * issuer).
   */
  OIDC_PROVIDER_CHANGED,
  /** An identity provider was deleted; its accounts remain, only their sign-in stops. */
  OIDC_PROVIDER_DELETED,
  /** An identity provider was enabled - its tokens are accepted again from this commit on. */
  OIDC_PROVIDER_ENABLED,
  /** An identity provider was disabled - its tokens are refused from this commit on. */
  OIDC_PROVIDER_DISABLED,
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
  AUDIT_LOG_ACCESSED,

  // Lokale Konten (ADR-0033, Entscheidung 13)
  /**
   * A local account's sessions were revoked by something other than the person's own sign-out -
   * here the reuse of a rotated refresh token; later a lock, a reset or a handover. Written under
   * the {@code local-auth} system actor with the reason in {@code after}; the person's own logout
   * and the routine expiry of a session are deliberately no event.
   */
  LOCAL_SESSION_REVOKED,
  /** A local account's password was changed by the person, with the current password verified. */
  LOCAL_PASSWORD_CHANGED,
  /**
   * The bootstrap administrator (the emergency access of the Systemverwaltung) was created by the
   * seed on the first start - live with a password on a fresh installation, as {@code INVITED}
   * without one on an existing installation (ADR-0033, Entscheidung 5).
   */
  LOCAL_ADMIN_SEEDED,
  /**
   * The bootstrap administrator was restored by {@code OPAA_LOCAL_ADMIN_RESET=force}: unlocked,
   * expiry cleared, new one-time password, every session ended - or recreated after deletion.
   */
  LOCAL_ADMIN_RESET,
  /**
   * A successful sign-in with the bootstrap account - the one audited sign-in: a privileged
   * emergency access, not a person (ADR-0033, Entscheidung 13).
   */
  LOCAL_BOOTSTRAP_ACCOUNT_LOGIN,
  /** The local account management was switched on - the LOCAL provider row enabled. */
  LOCAL_ACCOUNTS_ENABLED,
  /**
   * The local account management was switched off; the sessions of every regular local account
   * ended with it (system administrators keep theirs).
   */
  LOCAL_ACCOUNTS_DISABLED,
  /**
   * A local account was locked for the fixed lockout duration after too many failed sign-ins - the
   * one event of the affair, under the {@code local-auth} system actor; the single failed attempt
   * is never audited (ADR-0033, Entscheidungen 9 and 13).
   */
  LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS,

  // Mail-Infrastruktur (#1536, ADR-0033 Entscheidung 13)
  /**
   * The SMTP settings changed. The before/after payload never carries the password itself, only
   * whether one is stored - the same convention {@link #LLM_MODEL_CHANGED} uses for a model's
   * access key.
   */
  MAIL_SETTINGS_CHANGED,
  /** A mail template was overridden for a locale, replacing the delivered German default. */
  MAIL_TEMPLATE_CHANGED,
  /** A mail template override was removed; the delivered default applies again. */
  MAIL_TEMPLATE_RESET,
  /**
   * A test mail was sent from the administration. Distinct from a change event because it leaves
   * the installation - an auditor asking "who had this deployment send mail, and when" should find
   * it by its own name.
   */
  MAIL_TEST_SENT
}
