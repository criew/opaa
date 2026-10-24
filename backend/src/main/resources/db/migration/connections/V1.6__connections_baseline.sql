-- Module connections (ADR-0041): connection profiles and library connections, connected accounts
-- with their tokens and authorizations, and the connection log - partitioned by month and owned by
-- opaa_audit_owner like the audit trail (ADR-0015).

CREATE FUNCTION connection_profiles_guard_owner_only() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', '${flyway:defaultSchema}', 'pg_temp'
    AS $$
BEGIN
  IF NEW.ownership IN ('PERSON', 'BOTH') THEN
    RETURN NEW;
  END IF;
  IF EXISTS (SELECT 1 FROM library_connections c JOIN assets a ON a.id = c.library_id
      WHERE c.profile_id = NEW.id AND a.owner_only) THEN
    RAISE EXCEPTION 'profile % carries an owner-only library and must admit persons', NEW.id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END; $$;

CREATE FUNCTION library_connections_guard_owner_only() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', '${flyway:defaultSchema}', 'pg_temp'
    AS $$
DECLARE
  owning_user uuid;
  profile_ownership character varying;
BEGIN
  IF NEW.profile_id IS NULL THEN
    RETURN NEW;
  END IF;
  SELECT a.owner_user_id INTO owning_user FROM assets a
    WHERE a.id = NEW.library_id AND a.owner_only;
  IF NOT FOUND THEN
    RETURN NEW;
  END IF;
  SELECT p.ownership INTO profile_ownership FROM connection_profiles p
    WHERE p.id = NEW.profile_id;
  IF profile_ownership IS DISTINCT FROM 'PERSON'
      AND profile_ownership IS DISTINCT FROM 'BOTH' THEN
    RAISE EXCEPTION 'owner-only library % goes only onto a profile for persons', NEW.library_id
      USING ERRCODE = 'check_violation';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM connected_accounts c
      WHERE c.user_id = owning_user AND c.profile_id = NEW.profile_id) THEN
    RAISE EXCEPTION 'owner-only library % goes only onto a profile its owner has connected', NEW.library_id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END; $$;

CREATE FUNCTION opaa_connection_log_delete_expired_partitions() RETURNS SETOF text
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
  FROM connection_log_retention_settings WHERE id = 1 FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'connection_log_retention_settings has no row with id=1 - refusing to silently skip retention deletion';
  END IF;

  new_cutoff := current_month - (cfg.retention_months::text || ' months')::interval;

  FOR rec IN
    SELECT inhrelid::regclass AS partition_rel,
           inhrelid::regclass::text AS partition_name
    FROM pg_catalog.pg_inherits
    WHERE inhparent = 'connection_log'::regclass
  LOOP
    partition_month := to_date(
      substring(rec.partition_name from 'connection_log_(\d{4}_\d{2})$'), 'YYYY_MM');
    IF partition_month IS NOT NULL
       AND (partition_month + interval '1 month') <= new_cutoff THEN
      EXECUTE format('DROP TABLE %s', rec.partition_rel);
      RETURN NEXT rec.partition_name;
    END IF;
  END LOOP;

  UPDATE connection_log_retention_settings
  SET last_cutoff = GREATEST(cfg.last_cutoff, new_cutoff),
      last_run_month = current_month
  WHERE id = 1;

  RETURN;
END; $_$;

CREATE TABLE connected_accounts (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    user_id uuid NOT NULL,
    profile_id uuid NOT NULL,
    state character varying(16) NOT NULL,
    ended_cause character varying(30),
    account_label_ciphertext text,
    connected_at timestamp with time zone NOT NULL,
    reconnected_at timestamp with time zone,
    expired_at timestamp with time zone,
    version bigint NOT NULL,
    last_used_at timestamp with time zone,
    CONSTRAINT chk_connected_accounts_cause CHECK (((ended_cause IS NULL) OR (ended_cause IN ('SELF', 'EMERGENCY', 'ADDRESS_CHANGED', 'REGISTRATION_CHANGED', 'ACCOUNT_DEACTIVATED', 'PROFILE_DELETED', 'PROVIDER_REJECTED', 'SECRET_EXPIRED', 'LIBRARY_DELETED', 'PROFILE_CHANGED')))),
    CONSTRAINT chk_connected_accounts_cause_of_end CHECK ((((state)::text = 'CONNECTED'::text) = (ended_cause IS NULL))),
    CONSTRAINT chk_connected_accounts_state CHECK ((state IN ('CONNECTED', 'EXPIRED', 'DISCONNECTED')))
);

CREATE TABLE connection_authorizations (
    id uuid NOT NULL,
    state_hash character(64) NOT NULL,
    user_id uuid NOT NULL,
    profile_id uuid NOT NULL,
    profile_version bigint NOT NULL,
    purpose character varying(20) NOT NULL,
    library_id uuid,
    code_verifier_ciphertext text NOT NULL,
    redirect_uri character varying(2000) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    service_account_confirmed_at timestamp with time zone,
    responsible_type character varying(10),
    responsible_id uuid,
    account_change_confirmed_at timestamp with time zone,
    expected_issuer character varying(2000),
    issuer_required boolean,
    CONSTRAINT chk_connection_authorizations_library CHECK ((((purpose)::text = 'LIBRARY_RECONNECT'::text) = (library_id IS NOT NULL))),
    CONSTRAINT chk_connection_authorizations_purpose CHECK ((purpose IN ('LIBRARY_NEW', 'LIBRARY_RECONNECT', 'ACCOUNT')))
);

CREATE TABLE connection_grant_revocations (
    id uuid NOT NULL,
    profile_id uuid NOT NULL,
    order_ciphertext text NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_connection_grant_revocations_attempts CHECK ((attempts >= 0))
);

CREATE TABLE connection_log (
    event_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    event_type character varying(30) NOT NULL,
    actor_ref character varying(255) NOT NULL,
    person_ref character varying(255),
    profile_id uuid NOT NULL,
    profile_name character varying(255) NOT NULL,
    cause character varying(30),
    owner_kind character varying(10) NOT NULL,
    library_id uuid,
    account_label character varying(500),
    CONSTRAINT chk_connection_log_cause CHECK (((cause IS NULL) OR (cause IN ('SELF', 'EMERGENCY', 'ADDRESS_CHANGED', 'REGISTRATION_CHANGED', 'ACCOUNT_DEACTIVATED', 'PROFILE_DELETED', 'PROVIDER_REJECTED', 'SECRET_EXPIRED', 'LIBRARY_DELETED', 'PROFILE_CHANGED')))),
    CONSTRAINT chk_connection_log_cause_of_end CHECK (((event_type IN ('CONNECTED', 'RECONNECTED')) = (cause IS NULL))),
    CONSTRAINT chk_connection_log_event_type CHECK ((event_type IN ('CONNECTED', 'RECONNECTED', 'DISCONNECTED', 'EXPIRED', 'EMERGENCY_DISCONNECTED', 'DELETED'))),
    CONSTRAINT chk_connection_log_owner CHECK (((((owner_kind)::text = 'PERSON'::text) AND (person_ref IS NOT NULL) AND (library_id IS NULL) AND (account_label IS NULL)) OR (((owner_kind)::text = 'LIBRARY'::text) AND (library_id IS NOT NULL) AND (person_ref IS NULL)) OR (((owner_kind)::text = 'PROFILE'::text) AND (person_ref IS NULL) AND (library_id IS NULL) AND (account_label IS NULL))))
)
PARTITION BY RANGE (recorded_at);

CREATE TABLE connection_log_retention_settings (
    id integer DEFAULT 1 NOT NULL,
    retention_months integer NOT NULL,
    last_cutoff timestamp with time zone,
    last_run_month date,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT chk_connection_log_retention_months CHECK (((retention_months >= 6) AND (retention_months <= 24))),
    CONSTRAINT chk_connection_log_retention_singleton CHECK ((id = 1))
);

CREATE TABLE connection_person_states (
    user_id uuid NOT NULL,
    deactivated_since timestamp with time zone,
    dormant_since timestamp with time zone,
    checked_at timestamp with time zone NOT NULL,
    CONSTRAINT chk_connection_person_states_one_state CHECK (((deactivated_since IS NULL) OR (dormant_since IS NULL)))
);

CREATE TABLE connection_profiles (
    id uuid NOT NULL,
    name character varying(255) NOT NULL,
    source_type character varying(20),
    server_url character varying(2000) NOT NULL,
    auth_method character varying(32) NOT NULL,
    ownership character varying(16) NOT NULL,
    client_id character varying(255),
    client_secret_ciphertext text,
    client_secret_expires_on date,
    tenant character varying(255),
    scopes character varying(2000),
    connector_settings text,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    version bigint NOT NULL,
    locked_at timestamp with time zone,
    authorization_endpoint character varying(2000),
    token_endpoint character varying(2000),
    revocation_endpoint character varying(2000),
    sign_in_rejected_at timestamp with time zone,
    client_secret_expiry_warned_at timestamp with time zone,
    kind character varying(16) DEFAULT 'CONNECTOR'::character varying NOT NULL,
    responsible_group_id uuid,
    issuer character varying(2000),
    issuer_parameter_supported boolean,
    CONSTRAINT chk_connection_profiles_auth_method CHECK ((auth_method IN ('NONE', 'PERSONAL_SECRET', 'OAUTH', 'CLIENT_CREDENTIALS', 'SERVICE_ACCOUNT_KEY'))),
    CONSTRAINT chk_connection_profiles_kind CHECK ((kind IN ('CONNECTOR', 'MCP_SERVER'))),
    CONSTRAINT chk_connection_profiles_kind_source_type CHECK ((((kind)::text = 'CONNECTOR'::text) = (source_type IS NOT NULL))),
    CONSTRAINT chk_connection_profiles_ownership CHECK ((ownership IN ('LIBRARY', 'PERSON', 'BOTH')))
);

CREATE TABLE connection_tokens (
    id uuid NOT NULL,
    profile_id uuid NOT NULL,
    connected_account_id uuid,
    library_id uuid,
    kind character varying(16) NOT NULL,
    secret_ciphertext text NOT NULL,
    access_token_ciphertext text,
    access_token_expires_at timestamp with time zone,
    expires_at timestamp with time zone,
    issued_for character varying(2000) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    version bigint NOT NULL,
    expiry_warned_at timestamp with time zone,
    pending_user_id uuid,
    pending_expires_at timestamp with time zone,
    pending_account_label character varying(500),
    CONSTRAINT chk_connection_tokens_kind CHECK ((kind IN ('PERSONAL_SECRET', 'OAUTH'))),
    CONSTRAINT chk_connection_tokens_owner CHECK ((num_nonnulls(connected_account_id, library_id, pending_user_id) = 1)),
    CONSTRAINT chk_connection_tokens_pending CHECK (((pending_user_id IS NULL) = (pending_expires_at IS NULL)))
);

CREATE TABLE connector_type_policies (
    source_type character varying(20) NOT NULL,
    locked_at timestamp with time zone,
    updated_at timestamp with time zone NOT NULL,
    profile_required_at timestamp with time zone,
    own_address_stock character varying(16),
    CONSTRAINT chk_connector_type_policies_own_address_stock CHECK ((own_address_stock IN ('RUNS', 'LOCKED'))),
    CONSTRAINT chk_connector_type_policies_profile_requirement CHECK (((profile_required_at IS NULL) = (own_address_stock IS NULL)))
);

CREATE TABLE library_connections (
    library_id uuid NOT NULL,
    profile_id uuid,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    version bigint NOT NULL,
    account_label character varying(500),
    connected_by uuid,
    connected_at timestamp with time zone,
    responsible_type character varying(10),
    responsible_id uuid,
    ended_cause character varying(30),
    ended_at timestamp with time zone,
    CONSTRAINT chk_library_connections_ended CHECK ((((ended_cause IS NULL) = (ended_at IS NULL)) AND ((ended_cause IS NULL) OR (ended_cause IN ('SELF', 'EMERGENCY', 'ADDRESS_CHANGED', 'REGISTRATION_CHANGED', 'PROFILE_DELETED', 'PROVIDER_REJECTED', 'SECRET_EXPIRED', 'PROFILE_CHANGED'))))),
    CONSTRAINT chk_library_connections_responsible CHECK ((((responsible_type IS NULL) = (responsible_id IS NULL)) AND ((responsible_type IS NULL) OR (responsible_type IN ('USER', 'GROUP')))))
);

ALTER TABLE ONLY connected_accounts
    ADD CONSTRAINT connected_accounts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY connection_authorizations
    ADD CONSTRAINT connection_authorizations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY connection_grant_revocations
    ADD CONSTRAINT connection_grant_revocations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY connection_log
    ADD CONSTRAINT connection_log_pkey PRIMARY KEY (event_id, recorded_at);

ALTER TABLE ONLY connection_log_retention_settings
    ADD CONSTRAINT connection_log_retention_settings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY connection_person_states
    ADD CONSTRAINT connection_person_states_pkey PRIMARY KEY (user_id);

ALTER TABLE ONLY connection_profiles
    ADD CONSTRAINT connection_profiles_pkey PRIMARY KEY (id);

ALTER TABLE ONLY connection_tokens
    ADD CONSTRAINT connection_tokens_pkey PRIMARY KEY (id);

ALTER TABLE ONLY connector_type_policies
    ADD CONSTRAINT connector_type_policies_pkey PRIMARY KEY (source_type);

ALTER TABLE ONLY library_connections
    ADD CONSTRAINT library_connections_pkey PRIMARY KEY (library_id);

ALTER TABLE ONLY connected_accounts
    ADD CONSTRAINT ux_connected_accounts_id_profile UNIQUE (id, profile_id);

ALTER TABLE ONLY connected_accounts
    ADD CONSTRAINT ux_connected_accounts_user_profile UNIQUE (user_id, profile_id);

ALTER TABLE ONLY connection_authorizations
    ADD CONSTRAINT ux_connection_authorizations_state UNIQUE (state_hash);

ALTER TABLE ONLY connection_tokens
    ADD CONSTRAINT ux_connection_tokens_account UNIQUE (connected_account_id);

ALTER TABLE ONLY connection_tokens
    ADD CONSTRAINT ux_connection_tokens_library UNIQUE (library_id);

CREATE INDEX idx_connection_log_profile ON connection_log USING btree (organization_id, profile_id, recorded_at);

CREATE INDEX idx_connection_log_time_range ON connection_log USING btree (organization_id, recorded_at);

CREATE INDEX idx_connection_grant_revocations_next_attempt_at ON connection_grant_revocations USING btree (next_attempt_at);

CREATE INDEX ix_connected_accounts_profile ON connected_accounts USING btree (profile_id);

CREATE INDEX ix_connection_authorizations_expiry ON connection_authorizations USING btree (expires_at);

CREATE INDEX ix_connection_authorizations_library ON connection_authorizations USING btree (library_id);

CREATE INDEX ix_connection_authorizations_profile ON connection_authorizations USING btree (profile_id);

CREATE INDEX ix_connection_authorizations_user ON connection_authorizations USING btree (user_id, created_at);

CREATE INDEX ix_connection_person_states_deactivated ON connection_person_states USING btree (deactivated_since) WHERE (deactivated_since IS NOT NULL);

CREATE INDEX ix_connection_profiles_responsible_group ON connection_profiles USING btree (responsible_group_id);

CREATE INDEX ix_connection_tokens_pending_expiry ON connection_tokens USING btree (pending_expires_at);

CREATE INDEX ix_connection_tokens_pending_user ON connection_tokens USING btree (pending_user_id);

CREATE INDEX ix_connection_tokens_profile ON connection_tokens USING btree (profile_id);

CREATE INDEX ix_library_connections_connected_by ON library_connections USING btree (connected_by);

CREATE INDEX ix_library_connections_profile ON library_connections USING btree (profile_id);

CREATE UNIQUE INDEX ux_connection_profiles_name ON connection_profiles USING btree (lower((name)::text));

CREATE CONSTRAINT TRIGGER trg_connection_profiles_guard_owner_only AFTER UPDATE OF ownership ON connection_profiles DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION connection_profiles_guard_owner_only();

CREATE TRIGGER trg_library_connections_guard_owner_only BEFORE INSERT OR UPDATE OF profile_id ON library_connections FOR EACH ROW EXECUTE FUNCTION library_connections_guard_owner_only();

ALTER TABLE ONLY connected_accounts
    ADD CONSTRAINT fk_connected_accounts_profile FOREIGN KEY (profile_id) REFERENCES connection_profiles(id) ON DELETE CASCADE;

ALTER TABLE ONLY connected_accounts
    ADD CONSTRAINT fk_connected_accounts_user_organization FOREIGN KEY (user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE ONLY connection_authorizations
    ADD CONSTRAINT fk_connection_authorizations_library FOREIGN KEY (library_id) REFERENCES knowledge_libraries(id) ON DELETE CASCADE;

ALTER TABLE ONLY connection_authorizations
    ADD CONSTRAINT fk_connection_authorizations_profile FOREIGN KEY (profile_id) REFERENCES connection_profiles(id) ON DELETE CASCADE;

ALTER TABLE ONLY connection_authorizations
    ADD CONSTRAINT fk_connection_authorizations_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE connection_log
    ADD CONSTRAINT fk_connection_log_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT;

ALTER TABLE ONLY connection_person_states
    ADD CONSTRAINT fk_connection_person_states_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE ONLY connection_profiles
    ADD CONSTRAINT fk_connection_profiles_responsible_group FOREIGN KEY (responsible_group_id) REFERENCES groups(id) ON DELETE SET NULL;

ALTER TABLE ONLY connection_tokens
    ADD CONSTRAINT fk_connection_tokens_account FOREIGN KEY (connected_account_id, profile_id) REFERENCES connected_accounts(id, profile_id) ON DELETE CASCADE;

ALTER TABLE ONLY connection_tokens
    ADD CONSTRAINT fk_connection_tokens_library FOREIGN KEY (library_id) REFERENCES knowledge_libraries(id) ON DELETE CASCADE;

ALTER TABLE ONLY connection_tokens
    ADD CONSTRAINT fk_connection_tokens_pending_user FOREIGN KEY (pending_user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE ONLY connection_tokens
    ADD CONSTRAINT fk_connection_tokens_profile FOREIGN KEY (profile_id) REFERENCES connection_profiles(id) ON DELETE CASCADE;

ALTER TABLE ONLY library_connections
    ADD CONSTRAINT fk_library_connections_connected_by FOREIGN KEY (connected_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE ONLY library_connections
    ADD CONSTRAINT fk_library_connections_library FOREIGN KEY (library_id) REFERENCES knowledge_libraries(id) ON DELETE CASCADE;

ALTER TABLE ONLY library_connections
    ADD CONSTRAINT fk_library_connections_profile FOREIGN KEY (profile_id) REFERENCES connection_profiles(id) ON DELETE SET NULL;

INSERT INTO connection_log_retention_settings (id, retention_months, last_cutoff, last_run_month, updated_at)
VALUES (
  1, 12,
  date_trunc('month', now()) - interval '12 months',
  date_trunc('month', now())::date,
  now()
);

-- The fixed horizon of audit_log: three months back through 16 years forward, no DEFAULT
-- partition, so a write outside it fails loudly instead of escaping the deletion.
DO $$
DECLARE
  start_month date := date_trunc('month', now() - interval '3 months');
  partition_start date;
BEGIN
  FOR i IN 0..194 LOOP
    partition_start := (start_month + (i || ' months')::interval)::date;
    EXECUTE format(
      'CREATE TABLE %I PARTITION OF connection_log FOR VALUES FROM (%L) TO (%L)',
      'connection_log_' || to_char(partition_start, 'YYYY_MM'), partition_start,
      (partition_start + interval '1 month')::date);
  END LOOP;
END $$;

-- Ownership separation per ADR-0015, as for audit_log: the objects move to opaa_audit_owner, the
-- application account keeps only the narrow grants below. The membership is taken briefly and
-- given back.
DO $$
BEGIN
  EXECUTE format('GRANT opaa_audit_owner TO %I WITH SET TRUE', current_user);
END $$;

ALTER TABLE connection_log OWNER TO opaa_audit_owner;
ALTER TABLE connection_log_retention_settings OWNER TO opaa_audit_owner;
ALTER FUNCTION opaa_connection_log_delete_expired_partitions() OWNER TO opaa_audit_owner;
DO $$
DECLARE
  partition regclass;
BEGIN
  FOR partition IN SELECT inhrelid::regclass FROM pg_inherits WHERE inhparent = 'connection_log'::regclass LOOP
    EXECUTE format('ALTER TABLE %s OWNER TO opaa_audit_owner', partition);
  END LOOP;
END $$;

REVOKE ALL ON TABLE connection_log, connection_log_retention_settings FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION opaa_connection_log_delete_expired_partitions() FROM PUBLIC;
GRANT INSERT, SELECT ON TABLE connection_log TO CURRENT_USER;
GRANT SELECT ON TABLE connection_log_retention_settings TO CURRENT_USER;
GRANT UPDATE (retention_months, updated_at) ON TABLE connection_log_retention_settings TO CURRENT_USER;
GRANT EXECUTE ON FUNCTION opaa_connection_log_delete_expired_partitions() TO CURRENT_USER;

DO $$
BEGIN
  EXECUTE format('REVOKE opaa_audit_owner FROM %I', current_user);
END $$;
