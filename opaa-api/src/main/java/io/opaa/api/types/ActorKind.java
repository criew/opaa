package io.opaa.api.types;

/**
 * Who or what performed the action recorded by an {@link AuditLogEntry} - trennt Handeln von
 * Automatik (docs/features/security-and-compliance.md#der-protokollsatz). Mirrored by the database
 * check constraint {@code chk_audit_log_actor_kind} (migration 017); keep both in sync.
 */
public enum ActorKind {
  /** A person, identified only by their pseudonym ({@link AuditLogEntry#getActorRef()}). */
  USER,
  /** A service account, e.g. an API token used for machine-to-machine access. */
  SERVICE_ACCOUNT,
  /** An automated system process, e.g. a directory synchronisation run. */
  SYSTEM_PROCESS
}
