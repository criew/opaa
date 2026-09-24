package io.opaa.api.types;

/**
 * The kind of object an {@code AuditLogEntry} is about ({@code AuditLogEntry#getObjectType()},
 * backend module). A closed vocabulary, mirrored by the database check constraint {@code
 * chk_audit_log_object_type}; keep both in sync.
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
  DIRECTORY_SYNC_RUN,
  PROMPT_LIBRARY,
  PROMPT
}
