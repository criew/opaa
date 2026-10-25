SET LOCAL lock_timeout = '5s';

-- Checksum chain of the audit log, one chain per monthly partition (ADR-0059, Entscheidung 4).
-- A checkpoint records how far the chain run got: the number of entries chained from the start of
-- the month, the key of the last chained entry and the head hash. The primary key is the marker
-- against a second run writing the same position twice (ADR-0053, Entscheidung 7).
CREATE TABLE audit_chain_checkpoints (
    month date NOT NULL,
    position bigint NOT NULL,
    last_recorded_at timestamp with time zone,
    last_event_id uuid,
    head_hash bytea NOT NULL,
    algorithm character varying(40) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT audit_chain_checkpoints_pkey PRIMARY KEY (month, position),
    CONSTRAINT chk_audit_chain_checkpoints_month CHECK ((month = (date_trunc('month'::text, (month)::timestamp with time zone))::date)),
    CONSTRAINT chk_audit_chain_checkpoints_position CHECK ((position >= 0)),
    CONSTRAINT chk_audit_chain_checkpoints_last_key CHECK (((position = 0) = (last_recorded_at IS NULL) AND (position = 0) = (last_event_id IS NULL))),
    CONSTRAINT chk_audit_chain_checkpoints_hash CHECK ((octet_length(head_hash) = 32))
);

-- The seal of a closed month: the head hash over all its entries, written once together with the
-- AUDIT_LOG_MONTH_SEALED entry that carries it out of the database. One seal per month.
CREATE TABLE audit_chain_seals (
    month date NOT NULL,
    entry_count bigint NOT NULL,
    head_hash bytea NOT NULL,
    algorithm character varying(40) NOT NULL,
    sealed_at timestamp with time zone NOT NULL,
    seal_event_id uuid NOT NULL,
    CONSTRAINT audit_chain_seals_pkey PRIMARY KEY (month),
    CONSTRAINT uk_audit_chain_seals_event UNIQUE (seal_event_id),
    CONSTRAINT chk_audit_chain_seals_month CHECK ((month = (date_trunc('month'::text, (month)::timestamp with time zone))::date)),
    CONSTRAINT chk_audit_chain_seals_count CHECK ((entry_count >= 0)),
    CONSTRAINT chk_audit_chain_seals_hash CHECK ((octet_length(head_hash) = 32))
);

-- The owner role of the protocol takes the two tables and replaces its deletion function
-- (ADR-0015, Entscheidung 2); the membership is taken only for these statements.
DO $$
BEGIN
  EXECUTE format('GRANT opaa_audit_owner TO %I WITH SET TRUE', current_user);
END $$;

ALTER TABLE audit_chain_checkpoints OWNER TO opaa_audit_owner;
ALTER TABLE audit_chain_seals OWNER TO opaa_audit_owner;

-- Same contract as before, and a dropped partition takes its checkpoints and seal with it: the
-- chain of a month that no longer exists would otherwise read as a month whose entries were removed.
CREATE OR REPLACE FUNCTION opaa_audit_delete_expired_partitions() RETURNS SETOF text
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'pg_catalog', '${flyway:defaultSchema}', 'pg_temp'
    AS $_$ DECLARE
  cfg RECORD;
  current_month date := date_trunc('month', now())::date;
  new_cutoff timestamptz;
  rec RECORD;
  partition_month date;
BEGIN
  SELECT retention_months, last_cutoff INTO cfg
  FROM audit_retention_settings WHERE id = 1 FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'audit_retention_settings has no row with id=1 - the baseline should have seeded it; refusing to silently skip retention deletion';
  END IF;

  new_cutoff := current_month
    - (cfg.retention_months::text || ' months')::interval;

  FOR rec IN
    SELECT inhrelid::regclass AS partition_rel,
           inhrelid::regclass::text AS partition_name
    FROM pg_catalog.pg_inherits
    WHERE inhparent = 'audit_log'::regclass
  LOOP
    partition_month := to_date(
      substring(rec.partition_name from 'audit_log_(\d{4}_\d{2})$'), 'YYYY_MM');
    IF partition_month IS NOT NULL
       AND (partition_month + interval '1 month') <= new_cutoff THEN
      EXECUTE format('DROP TABLE %s', rec.partition_rel);
      DELETE FROM audit_chain_checkpoints WHERE month = partition_month;
      DELETE FROM audit_chain_seals WHERE month = partition_month;
      RETURN NEXT rec.partition_name;
    END IF;
  END LOOP;

  UPDATE audit_retention_settings
  SET last_cutoff = GREATEST(cfg.last_cutoff, new_cutoff),
      last_run_month = current_month
  WHERE id = 1;

  RETURN;
END; $_$;

ALTER FUNCTION opaa_audit_delete_expired_partitions() OWNER TO opaa_audit_owner;

REVOKE ALL ON TABLE audit_chain_checkpoints, audit_chain_seals FROM PUBLIC;
GRANT INSERT, SELECT ON TABLE audit_chain_checkpoints, audit_chain_seals TO CURRENT_USER;

DO $$
BEGIN
  EXECUTE format('REVOKE opaa_audit_owner FROM %I', current_user);
END $$;
