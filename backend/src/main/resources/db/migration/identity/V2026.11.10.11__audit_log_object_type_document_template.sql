SET LOCAL lock_timeout = '5s';

-- Document templates and agents are objects of the audit log of their own. AGENT is added by a
-- parallel change as well, so either order of the two scripts ends with both. Only values join the
-- list, so the running release keeps writing what it wrote before. audit_log belongs to
-- opaa_audit_owner (ADR-0015): the migration account takes the membership for these statements and
-- gives it back, as the baseline does. The list keeps SERVICE_ACCOUNT, so the script follows the one
-- that added it and refuses to run before it, which would drop that value again.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'chk_audit_log_object_type'
      AND pg_get_constraintdef(oid) LIKE '%SERVICE_ACCOUNT%'
  ) THEN
    RAISE EXCEPTION 'audit_log object type SERVICE_ACCOUNT missing: apply V2026.11.05.33 first';
  END IF;
  EXECUTE format('GRANT opaa_audit_owner TO %I WITH SET TRUE', current_user);
END $$;

ALTER TABLE audit_log DROP CONSTRAINT chk_audit_log_object_type;

ALTER TABLE audit_log ADD CONSTRAINT chk_audit_log_object_type CHECK ((object_type IN ('KNOWLEDGE_LIBRARY', 'SPACE', 'GROUP', 'ASSET_GRANT', 'USER_ACCOUNT', 'API_TOKEN', 'SYSTEM_SETTING', 'AUDIT_LOG', 'DIRECTORY_SYNC_RUN', 'PROMPT_LIBRARY', 'PROMPT', 'SERVICE_ACCOUNT', 'AGENT', 'DOCUMENT_TEMPLATE')));

DO $$
BEGIN
  EXECUTE format('REVOKE opaa_audit_owner FROM %I', current_user);
END $$;
