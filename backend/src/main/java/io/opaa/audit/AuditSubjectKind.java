package io.opaa.audit;

/**
 * The kind of the affected rights subject ({@link AuditLogEntry#getSubjectRef()}) - a person
 * (itself pseudonymised) or a group. Null on entries that have no rights subject at all, e.g. a
 * governance settings change. Mirrored by the database check constraint {@code
 * chk_audit_log_subject}; keep both in sync.
 */
public enum AuditSubjectKind {
  USER,
  GROUP
}
