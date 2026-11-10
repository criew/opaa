SET LOCAL lock_timeout = '5s';

-- The step log of agent runs and the log of their sandboxes (ADR-0070, Entscheidung 4, Bestand b;
-- ADR-0073): no account reference, no search text, no script or file content. Partitioned by day,
-- owned by opaa_audit_owner like the diagnostic context log: the application account appends and
-- reads; only opaa_agent_run_logs_maintain() creates the coming days and drops expired ones.

CREATE TABLE agent_run_log_retention_settings (
    id integer DEFAULT 1 NOT NULL,
    step_log_retention_days integer NOT NULL,
    sandbox_log_retention_days integer NOT NULL,
    last_run_day date,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT agent_run_log_retention_settings_pkey PRIMARY KEY (id),
    CONSTRAINT chk_agent_run_log_retention_singleton CHECK (id = 1),
    CONSTRAINT chk_agent_step_log_retention_days
        CHECK (step_log_retention_days >= 1 AND step_log_retention_days <= 30),
    CONSTRAINT chk_agent_sandbox_log_retention_days
        CHECK (sandbox_log_retention_days >= 1
               AND sandbox_log_retention_days <= step_log_retention_days)
);

INSERT INTO agent_run_log_retention_settings
    (id, step_log_retention_days, sandbox_log_retention_days, last_run_day, updated_at)
VALUES (1, 7, 7, NULL, now());

CREATE TABLE agent_step_log (
    run_id uuid NOT NULL,
    step_index integer NOT NULL,
    recorded_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    organization_id uuid NOT NULL,
    tool character varying(64) NOT NULL,
    status character varying(30) NOT NULL,
    hit_count integer,
    duration_ms integer NOT NULL,
    abort_reason character varying(30),
    CONSTRAINT agent_step_log_pkey PRIMARY KEY (run_id, step_index, recorded_at),
    CONSTRAINT chk_agent_step_log_step_index CHECK (step_index >= 0),
    CONSTRAINT chk_agent_step_log_hit_count CHECK (hit_count IS NULL OR hit_count >= 0),
    CONSTRAINT chk_agent_step_log_duration CHECK (duration_ms >= 0)
)
PARTITION BY RANGE (recorded_at);

CREATE INDEX idx_agent_step_log_run ON agent_step_log (organization_id, run_id);

CREATE TABLE agent_sandbox_log (
    run_id uuid NOT NULL,
    session_ref character varying(64) NOT NULL,
    recorded_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    organization_id uuid NOT NULL,
    tool character varying(64) NOT NULL,
    status character varying(30) NOT NULL,
    script_runs integer NOT NULL,
    duration_ms bigint NOT NULL,
    file_count integer NOT NULL,
    file_bytes bigint NOT NULL,
    abort_reason character varying(30),
    CONSTRAINT agent_sandbox_log_pkey PRIMARY KEY (run_id, session_ref, recorded_at),
    CONSTRAINT chk_agent_sandbox_log_counts
        CHECK (script_runs >= 0 AND duration_ms >= 0 AND file_count >= 0 AND file_bytes >= 0)
)
PARTITION BY RANGE (recorded_at);

CREATE INDEX idx_agent_sandbox_log_run ON agent_sandbox_log (organization_id, run_id);

-- Creates the day partitions from yesterday through 30 days ahead and drops every day partition
-- that ended at least the retention period ago; returns the names it dropped. No DEFAULT partition:
-- a write outside the created days fails instead of escaping the deletion.
CREATE FUNCTION opaa_agent_run_logs_maintain() RETURNS SETOF text
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', '${flyway:defaultSchema}', 'pg_temp'
    AS $_$ DECLARE
  cfg RECORD;
  today date := (now() AT TIME ZONE 'UTC')::date;
  log_table text;
  retention integer;
  day date;
  rec RECORD;
  partition_day date;
BEGIN
  SELECT step_log_retention_days, sandbox_log_retention_days INTO cfg
  FROM agent_run_log_retention_settings WHERE id = 1 FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'agent_run_log_retention_settings has no row with id=1 - refusing to skip the deletion';
  END IF;

  FOREACH log_table IN ARRAY ARRAY['agent_step_log', 'agent_sandbox_log'] LOOP
    retention := CASE log_table
      WHEN 'agent_step_log' THEN cfg.step_log_retention_days
      ELSE cfg.sandbox_log_retention_days END;

    FOR i IN -1..30 LOOP
      day := today + i;
      IF to_regclass(format('%I.%I', '${flyway:defaultSchema}',
                            log_table || '_' || to_char(day, 'YYYYMMDD'))) IS NULL THEN
        EXECUTE format(
          'CREATE TABLE %s PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
          format('%I.%I', '${flyway:defaultSchema}', log_table || '_' || to_char(day, 'YYYYMMDD')),
          format('%I.%I', '${flyway:defaultSchema}', log_table),
          (day::timestamp AT TIME ZONE 'UTC'), ((day + 1)::timestamp AT TIME ZONE 'UTC'));
      END IF;
    END LOOP;

    FOR rec IN
      SELECT inhrelid::regclass AS partition_rel, inhrelid::regclass::text AS partition_name
      FROM pg_catalog.pg_inherits
      WHERE inhparent = format('%I.%I', '${flyway:defaultSchema}', log_table)::regclass
    LOOP
      partition_day := to_date(
        substring(rec.partition_name from '_(\d{8})$'), 'YYYYMMDD');
      IF partition_day IS NOT NULL AND partition_day + 1 <= today - retention THEN
        EXECUTE format('DROP TABLE %s', rec.partition_rel);
        RETURN NEXT rec.partition_name;
      END IF;
    END LOOP;
  END LOOP;

  UPDATE agent_run_log_retention_settings SET last_run_day = today WHERE id = 1;
  RETURN;
END; $_$;

-- Ownership separation per ADR-0015, as for the diagnostic context log; the membership is taken
-- briefly and given back.
DO $$
BEGIN
  EXECUTE format('GRANT opaa_audit_owner TO %I WITH SET TRUE', current_user);
END $$;

ALTER TABLE agent_step_log OWNER TO opaa_audit_owner;
ALTER TABLE agent_sandbox_log OWNER TO opaa_audit_owner;
ALTER TABLE agent_run_log_retention_settings OWNER TO opaa_audit_owner;
ALTER FUNCTION opaa_agent_run_logs_maintain() OWNER TO opaa_audit_owner;

-- Run once as the owner, so the first days exist and belong to opaa_audit_owner.
SELECT count(*) FROM opaa_agent_run_logs_maintain();

REVOKE ALL ON TABLE agent_step_log, agent_sandbox_log, agent_run_log_retention_settings FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION opaa_agent_run_logs_maintain() FROM PUBLIC;
GRANT INSERT, SELECT ON TABLE agent_step_log, agent_sandbox_log TO CURRENT_USER;
GRANT SELECT ON TABLE agent_run_log_retention_settings TO CURRENT_USER;
GRANT UPDATE (step_log_retention_days, sandbox_log_retention_days, updated_at)
    ON TABLE agent_run_log_retention_settings TO CURRENT_USER;
GRANT EXECUTE ON FUNCTION opaa_agent_run_logs_maintain() TO CURRENT_USER;

DO $$
BEGIN
  EXECUTE format('REVOKE opaa_audit_owner FROM %I', current_user);
END $$;
