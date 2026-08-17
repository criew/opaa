package io.opaa.audit;

/**
 * The kind of object an {@link AuditLogEntry} is about ({@link AuditLogEntry#getObjectType()}). A
 * closed vocabulary, mirrored by the database check constraint {@code chk_audit_log_object_type}
 * (migration 017); keep both in sync.
 */
public enum AuditObjectType {
  KNOWLEDGE_LIBRARY,
  SPACE,
  GROUP,
  ASSET_GRANT,
  USER_ACCOUNT,
  API_TOKEN,
  SYSTEM_SETTING,
  AUDIT_LOG,
  DIRECTORY_SYNC_RUN
}
