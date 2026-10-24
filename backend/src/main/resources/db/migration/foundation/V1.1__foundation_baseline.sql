-- Module foundation: the tenant root (organizations) every other table is scoped to, with its single
-- default organization; the instance register, installation counters, scheduled-run locks, the
-- outbound proxy setting and notify_cache_invalidation, the trigger function of the invalidation bus.

CREATE FUNCTION notify_cache_invalidation() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', '${flyway:defaultSchema}', 'pg_temp'
    AS $$
BEGIN
  IF TG_LEVEL = 'ROW' AND TG_OP = 'UPDATE' AND TG_NARGS > 1
      AND (to_jsonb(OLD) - TG_ARGV[1:TG_NARGS - 1])
          = (to_jsonb(NEW) - TG_ARGV[1:TG_NARGS - 1]) THEN
    RETURN NULL;
  END IF;
  PERFORM pg_notify('opaa_invalidation', TG_ARGV[0]);
  RETURN NULL;
END; $$;

CREATE TABLE backend_instance_fingerprint_key (
    id integer NOT NULL,
    hmac_key bytea NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT chk_backend_instance_fingerprint_key_length CHECK ((octet_length(hmac_key) = 32)),
    CONSTRAINT chk_backend_instance_fingerprint_key_singleton CHECK ((id = 1))
);

CREATE TABLE backend_instances (
    id uuid NOT NULL,
    host_name text NOT NULL,
    version text NOT NULL,
    oldest_compatible_version text NOT NULL,
    started_at timestamp with time zone NOT NULL,
    heartbeat_at timestamp with time zone NOT NULL,
    stopped_at timestamp with time zone,
    fingerprint jsonb NOT NULL,
    fingerprint_hash text NOT NULL,
    CONSTRAINT chk_backend_instances_fingerprint_object CHECK ((jsonb_typeof(fingerprint) = 'object'::text))
);

CREATE TABLE installation_counters (
    counter_key character varying(100) NOT NULL,
    window_seconds integer NOT NULL,
    window_start timestamp with time zone NOT NULL,
    current_count integer NOT NULL,
    previous_count integer NOT NULL,
    counted_at timestamp with time zone NOT NULL,
    quiet_until timestamp with time zone,
    CONSTRAINT ck_installation_counters_window CHECK ((window_seconds > 0))
);

CREATE TABLE organizations (
    id uuid NOT NULL,
    name character varying(255) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE outbound_proxy_settings (
    id integer NOT NULL,
    proxy_host text,
    proxy_port integer,
    no_proxy text NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT chk_outbound_proxy_settings_host_and_port CHECK (((proxy_host IS NULL) = (proxy_port IS NULL))),
    CONSTRAINT chk_outbound_proxy_settings_port CHECK (((proxy_port >= 1) AND (proxy_port <= 65535))),
    CONSTRAINT chk_outbound_proxy_settings_singleton CHECK ((id = 1))
);

CREATE TABLE shedlock (
    name character varying(64) NOT NULL,
    lock_until timestamp without time zone NOT NULL,
    locked_at timestamp without time zone NOT NULL,
    locked_by character varying(255) NOT NULL
);

ALTER TABLE ONLY backend_instance_fingerprint_key
    ADD CONSTRAINT backend_instance_fingerprint_key_pkey PRIMARY KEY (id);

ALTER TABLE ONLY backend_instances
    ADD CONSTRAINT backend_instances_pkey PRIMARY KEY (id);

ALTER TABLE ONLY installation_counters
    ADD CONSTRAINT installation_counters_pkey PRIMARY KEY (counter_key, window_seconds);

ALTER TABLE ONLY organizations
    ADD CONSTRAINT organizations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY outbound_proxy_settings
    ADD CONSTRAINT outbound_proxy_settings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY shedlock
    ADD CONSTRAINT shedlock_pkey PRIMARY KEY (name);

CREATE TRIGGER trg_outbound_proxy_settings_invalidation AFTER INSERT OR DELETE OR UPDATE OR TRUNCATE ON outbound_proxy_settings FOR EACH STATEMENT EXECUTE FUNCTION notify_cache_invalidation('outbound-proxy');

INSERT INTO organizations (id, name) VALUES ('00000000-0000-0000-0000-000000000001', 'Default');
