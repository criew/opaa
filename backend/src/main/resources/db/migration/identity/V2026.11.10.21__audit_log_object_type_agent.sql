SET LOCAL lock_timeout = '5s';

-- An agent and a document template are objects of the audit log of their own. Only
-- values join the list, so the running release keeps writing what it wrote before; both values
-- stand here and in the document template's migration, so either order leaves both. audit_log
-- belongs to opaa_audit_owner (ADR-0015): the migration account takes the membership for these
-- statements and gives it back, as the baseline does.
--
-- The list below includes SERVICE_ACCOUNT, which V2026.11.05.33 adds with a list of its own: this
-- script builds on it and refuses to run before it, so an installation that lacks it receives it
-- first and this list stays the last word.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'chk_audit_log_object_type'
      AND pg_get_constraintdef(oid) LIKE '%SERVICE_ACCOUNT%'
  ) THEN
    RAISE EXCEPTION 'chk_audit_log_object_type lacks SERVICE_ACCOUNT: apply V2026.11.05.33 first';
  END IF;
END $$;

DO $$
BEGIN
  EXECUTE format('GRANT opaa_audit_owner TO %I WITH SET TRUE', current_user);
END $$;

ALTER TABLE audit_log DROP CONSTRAINT chk_audit_log_object_type;

ALTER TABLE audit_log ADD CONSTRAINT chk_audit_log_object_type CHECK ((object_type IN ('KNOWLEDGE_LIBRARY', 'SPACE', 'GROUP', 'ASSET_GRANT', 'USER_ACCOUNT', 'API_TOKEN', 'SYSTEM_SETTING', 'AUDIT_LOG', 'DIRECTORY_SYNC_RUN', 'PROMPT_LIBRARY', 'PROMPT', 'SERVICE_ACCOUNT', 'AGENT', 'DOCUMENT_TEMPLATE')));

DO $$
BEGIN
  EXECUTE format('REVOKE opaa_audit_owner FROM %I', current_user);
END $$;
