SET LOCAL lock_timeout = '5s';

-- The version of the event contract (AuditEventCatalog) an entry was written under; NULL for the
-- entries written before it. audit_log belongs to opaa_audit_owner (ADR-0015): the migration
-- account takes the membership for this one statement and gives it back, as the baseline does.
DO $$
BEGIN
  EXECUTE format('GRANT opaa_audit_owner TO %I WITH SET TRUE', current_user);
END $$;

ALTER TABLE audit_log ADD COLUMN schema_version smallint;

DO $$
BEGIN
  EXECUTE format('REVOKE opaa_audit_owner FROM %I', current_user);
END $$;
