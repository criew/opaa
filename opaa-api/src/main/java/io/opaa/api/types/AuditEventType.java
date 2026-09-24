package io.opaa.api.types;

/**
 * The closed list of events the first protocol stage records
 * (docs/features/security-and-compliance.md#die-ereignisse-der-ersten-stufe). "Was hier nicht
 * steht, wird in der ersten Stufe nicht geschrieben" - the list is deliberately closed, not a
 * category with a free-text detail: {@code AuditLogEntry#getEventType()} (backend module) is this
 * enum, not a {@code String}. This enum is the sole write guard; there is no longer a matching
 * database check constraint, so adding a value here does not require a migration.
 */
public enum AuditEventType {

  // Rechte an Assets
  /** Includes co-shares originating from the share chain ("Mitfreigaben aus der Freigabekette"). */
  ASSET_GRANT_GRANTED,
  ASSET_GRANT_CHANGED,
  ASSET_GRANT_REVOKED,
  /** A time-limited grant expiring is itself an event, the moment it takes effect. */
  ASSET_GRANT_EXPIRED,
  /**
   * Change of an asset's visibility or listedness (visibility, listed) - also the event a connector
   * library's clamp to a newly lowered share cap fires under (#797), since the clamp is materially
   * the same change an owner's own edit makes, just actor and cause differ.
   */
  ASSET_VISIBILITY_CHANGED,
  /**
   * A library's Fremdzugangsfreigabe was set or taken back (docs/features/external-access.md#die-
   * freigabe-der-bibliothek) - the same kind of reach field as {@link #ASSET_VISIBILITY_CHANGED},
   * but one that additionally decides about the Hausgrenze, which is why it is its own event rather
   * than a payload variant of that one. Carries the direction and the expiry date, never a name of
   * anyone holding a token.
   */
  ASSET_EXTERNAL_ACCESS_CHANGED,
  /**
   * A library's Fremdzugangsfreigabe stopped taking effect without anyone acting - carrying the
   * Anlass, today always the expiry of its mandatory Befristung. #797 decided the connector share
   * cap ({@link #CONNECTOR_LIBRARY_SHARE_LIMIT_CHANGED}) bounds only visibility and listed, not
   * this field - a lowered cap therefore never writes this event. Written under a system actor,
   * like every other expiry event.
   */
  ASSET_EXTERNAL_ACCESS_EXPIRED,

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
  /** A prompt library was created, renamed or re-described, or deleted with its prompts. */
  PROMPT_LIBRARY_CREATED,
  PROMPT_LIBRARY_CHANGED,
  PROMPT_LIBRARY_DELETED,
  /**
   * A prompt was added to, changed in or removed from its library. The object is the prompt, the
   * payload names its library; the prompt text never appears.
   */
  PROMPT_CREATED,
  PROMPT_CHANGED,
  PROMPT_DELETED,
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
   * Somebody was made responsible for an internal group (#1814, ADR-0036 Entscheidung 4).
   * Deliberately an audit event and no history row: responsibility carries no read access and is
   * therefore meaningless for "who could read what on day X" (ADR-0036, Entscheidung 8).
   */
  GROUP_STEWARD_APPOINTED,
  /** The reverse of {@link #GROUP_STEWARD_APPOINTED} - including handing responsibility over. */
  GROUP_STEWARD_DISMISSED,
  /**
   * The system administration named a contact point of a provider group (#1875, ADR-0036
   * Entscheidung 9) - a Verwaltungsakt that changes nothing about the group and grants no
   * maintenance right; it entitles the person to set and release that group's protection mark.
   */
  GROUP_CONTACT_APPOINTED,
  /**
   * The reverse of {@link #GROUP_CONTACT_APPOINTED}, by a decision or because the person left the
   * group - in the latter case recorded by the process, with no acting person.
   */
  GROUP_CONTACT_DISMISSED,
  /**
   * An internal group was released for use by other people granting rights, or the release was
   * taken back (ADR-0036, Entscheidung 9). A reach field like {@link #ASSET_VISIBILITY_CHANGED}: it
   * decides who may name this group as a grant subject at all.
   */
  GROUP_RELEASE_CHANGED,
  /**
   * The protection mark of the staff council and the comparable bodies was set or released
   * (ADR-0036, Entscheidung 9) - by the body itself, never by the administration, the same
   * treatment {@link #LIBRARY_DIAGNOSTICS_LOCK_CHANGED} gets.
   */
  GROUP_PROTECTION_CHANGED,
  /**
   * A system administrator who is no steward of the group read its member list (ADR-0036,
   * Entscheidung 9: "der Abruf ist ein Audit-Ereignis"; Personalrat A6). The administration may see
   * who is in a group, and that it looked is on the record; a steward reading their own group's
   * list writes nothing.
   */
  GROUP_MEMBERS_READ,
  /**
   * An asset of any type associated with (made available in) a space - recorded for every
   * association, not only the mixed-audience case; whether the audience was mixed is a separate
   * fact (the owner notification), not part of this event's own condition. See {@link
   * #ASSET_DETACHED_FROM_SPACE} for the reverse operation - the two are always distinguishable by
   * type, never inferred from before/after payload shape.
   */
  ASSET_SHARED_TO_SPACE,
  /** The reverse of {@link #ASSET_SHARED_TO_SPACE} - an asset detached from a space. */
  ASSET_DETACHED_FROM_SPACE,
  /**
   * {@link #ASSET_SHARED_TO_SPACE} as recorded while only libraries could be associated. Never
   * written; it stays so those protocol rows remain readable, since {@code audit_log} cannot be
   * rewritten.
   */
  LIBRARY_SHARED_TO_SPACE,
  /** {@link #ASSET_DETACHED_FROM_SPACE} under its former name - read-only like the one above. */
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
  /**
   * A personal access token stopped working without anyone revoking it - it reached its expiry, or
   * the lifecycle of its owner's account ended it (ADR-0035, Entscheidung 2). With a mandatory
   * expiry the silent end is the common case, and an access that ends without an entry is the same
   * gap in the trail as one that begins without one; {@code after.reason} names which of the two.
   */
  API_TOKEN_EXPIRED,
  /** One entry per effected change from a directory sync run, linked via {@code correlationRef}. */
  DIRECTORY_SYNC_CHANGE_APPLIED,
  /**
   * The header entry of a directory sync run, with its outcome. For a run a person confirmed, the
   * actor is that person and {@code reason} their stated one (security-and-compliance.md: "oberhalb
   * der Schwelle, mit der bestätigenden Person und ihrem Anlass"); every other run is written under
   * the directory-sync system process actor.
   */
  DIRECTORY_SYNC_RUN_COMPLETED,
  /**
   * A plan above the plausibility threshold was discarded without being applied (#1816, ADR-0036
   * Entscheidung 3). The counterpart of a confirmation, which is the run's own header entry - a
   * decision that leaves no trace is no decision.
   */
  DIRECTORY_SYNC_PLAN_DISCARDED,
  /**
   * An account lost its access because the directory reports it as disabled or no longer reports it
   * at all (#1818, ADR-0036 Entscheidung 3). One entry per account, linked to the run's header
   * entry via {@code correlationRef}.
   */
  DIRECTORY_ACCOUNT_LOCKED,
  /** The directory reports the account as enabled again; the lock is taken back (#1818). */
  DIRECTORY_ACCOUNT_UNLOCKED,

  // Systemeinstellungen
  GOVERNANCE_SETTINGS_CHANGED,
  /** Includes enabling the network address field, per the specification's explicit requirement. */
  AUDIT_LOG_CONFIGURATION_CHANGED,
  /**
   * The maximum retention period of the rights history changed (ADR-0036, Entscheidung 8). Its own
   * type rather than a payload of {@link #AUDIT_LOG_CONFIGURATION_CHANGED}: the two periods bound
   * different holdings - the protocol says <em>that</em> something happened, the history says
   * <em>for which span</em> a right was in force - and the personnel council's extract has to name
   * each of them on its own.
   */
  PERMISSION_HISTORY_RETENTION_CHANGED,
  /** Covers both model defaults and the approval of external models. */
  MODEL_POLICY_CHANGED,
  /**
   * A system administrator set or changed a connector library's share cap - the ceiling on {@code
   * visibility}/{@code listed} #797 introduces. Distinct from {@link #ASSET_VISIBILITY_CHANGED},
   * which the same call also writes whenever lowering the cap clamps the library's own, wider
   * setting back down to it.
   */
  CONNECTOR_LIBRARY_SHARE_LIMIT_CHANGED,
  /**
   * A capability was granted to an account, a group or to all accounts (ADR-0036, Entscheidung 5).
   * A governance event rather than an ordinary permission change: withdrawing a capability from all
   * accounts changes the working conditions of every employee, so the personnel council's extract
   * has to show it.
   */
  CAPABILITY_GRANTED,
  /** The reverse of {@link #CAPABILITY_GRANTED} - a capability grant withdrawn. */
  CAPABILITY_REVOKED,
  /**
   * Somebody looked at what a transfer of rights would move (#1834, ADR-0036 Entscheidung 10).
   * Written on every preview, including the ones nobody carries out: the preview is a reading of
   * everything one subject holds, and that it was read is the record - exactly as for a retrieved
   * Stichtagsauskunft.
   */
  PERMISSION_TRANSFER_PREVIEWED,
  /**
   * The rights of one subject were transferred to another (#1834). Carries source, target, scope
   * and the number of rows moved; the interval rows themselves carry the same transfer id and
   * outlive this entry.
   */
  PERMISSION_TRANSFER_EXECUTED,
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
  /**
   * A Suchdiagnose ran in the rights context of a group <em>and</em> of a space (#1835, ADR-0036
   * Entscheidung 7): acting person, profile and space, one entry per run and none per query. A
   * profile run without a space context writes nothing - it says what a group reaches across the
   * organization, which is no statement about an individual. {@code target_ref} stays the group id,
   * so "kein Personenbezug im Protokoll" remains a property of the structure.
   */
  SEARCH_DIAGNOSIS_PROFILE_RUN,

  // Zugriff auf die Protokolldaten selbst
  /** Any read, evaluation or export of audit data, including rejected attempts (see outcome). */
  AUDIT_LOG_ACCESSED,
  /**
   * One Stichtagsauskunft out of the Rechtehistorie, rejected attempts included (ADR-0036,
   * Entscheidung 8; Personalrat D3). Its own type rather than an {@link #AUDIT_LOG_ACCESSED}
   * payload: the two holdings are separate - the protocol says <em>that</em> something happened,
   * the history <em>for which span</em> a right was in force - and the object named here is the
   * queried library or space, not the log itself.
   */
  PERMISSION_HISTORY_ACCESSED,

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
  MAIL_TEST_SENT,

  // Verwaltung lokaler Konten (#1537, ADR-0033 Entscheidungen 11 and 13). Subject is the account
  // as a pseudonym; no event carries an address, a name or the value of the creation reason.
  /** A local account was created by an administrator; {@code after} names mode and expiry. */
  LOCAL_USER_CREATED,
  /**
   * The invitation link was issued; {@code after.deliveryPath} says whether it went out by mail or
   * was handed to the administrator for hand-over (MAIL_SENT, MAIL_FAILED, LINK_DISPLAYED).
   */
  LOCAL_USER_INVITED,
  /**
   * An administrator changed the account: before/after for {@code expiresAt} only, otherwise just
   * the names of the changed fields in {@code after.changedFields}.
   */
  LOCAL_USER_CHANGED,
  /**
   * Locked by an administrator or by the daily run for inactivity; {@code after.reason} says which.
   */
  LOCAL_USER_LOCKED,
  /** Unlocked by an administrator. */
  LOCAL_USER_UNLOCKED,
  /**
   * An administrator issued a password-reset link; {@code after.deliveryPath} as for the
   * invitation.
   */
  LOCAL_USER_PASSWORD_RESET_REQUESTED,
  /** An administrator set a generated password; the change is forced at the next sign-in. */
  LOCAL_USER_PASSWORD_GENERATED,
  /** A local account that owned nothing was deleted; its pseudonym stays in the trail. */
  LOCAL_USER_DELETED,
  /**
   * The settings row of the local account management changed; before/after of the changed keys
   * only.
   */
  LOCAL_ACCOUNTS_SETTINGS_CHANGED,
  /**
   * The person set a password through an invitation or reset link (ADR-0033, Entscheidung 11);
   * {@code after.purpose} names which of the two.
   */
  LOCAL_PASSWORD_SET,
  /**
   * A local account was created by self-registration (system process {@code local-auth}); the
   * address stays unconfirmed until the verification link is redeemed.
   */
  LOCAL_USER_REGISTERED,
  /**
   * An administrator started the handover of a local account to a provider identity (ADR-0033,
   * Entscheidung 12); {@code after} names the chosen provider and the delivery path of the link,
   * never the reason and never a subject.
   */
  LOCAL_USER_HANDOVER_REQUESTED,
  /**
   * The person redeemed the handover: the account now belongs to the provider identity. {@code
   * after} carries the provider id and the counts of what moved - never the provider subject
   * (ADR-0033, Entscheidung 13).
   */
  LOCAL_USER_HANDED_OVER,

  // Fremdzugaenge (#1717, ADR-0035, docs/features/external-access.md)
  /**
   * The channel settings of the external access changed - the installation-wide switch, the token
   * lifetime ceiling, the per-token quota, the networks of the channel or the threshold of the mass
   * retrieval alert; before/after of the changed keys only. The instructions text for foreign tools
   * is deliberately <em>not</em> part of this event: it changes no reach, only the wording of a
   * request to a foreign model, and the closed list of docs/features/security-and-compliance.md
   * does not name it.
   */
  EXTERNAL_ACCESS_SETTINGS_CHANGED
}
