-- Module rights: groups and their directory synchronisation, the asset shell, grants and
-- capabilities, the permission and account-state history with its retention setting, transfers and
-- succession cases - including the delivered capabilities of every organization and the trigger
-- that delivers them to later ones.

CREATE FUNCTION account_state_issuer_key(issuer text) RETURNS text
    LANGUAGE sql IMMUTABLE
    SET search_path TO 'pg_catalog', '${flyway:defaultSchema}', 'pg_temp'
    AS $_$
  SELECT rtrim(regexp_replace(issuer, '^[\x01-\x20]+|[\x01-\x20]+$', '', 'g'), '/')
$_$;

CREATE FUNCTION account_state_on_credentials_change() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', '${flyway:defaultSchema}', 'pg_temp'
    AS $$
BEGIN
  PERFORM account_state_reconcile(NEW.user_id, 'ACCOUNT_REACTIVATED',
      TG_OP = 'INSERT');
  RETURN NULL;
END; $$;

CREATE FUNCTION account_state_on_provider_change() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', '${flyway:defaultSchema}', 'pg_temp'
    AS $$
DECLARE
  affected uuid;
  old_key text;
  new_key text;
  touches_local boolean := false;
BEGIN
  IF TG_OP <> 'INSERT' THEN
    IF OLD.provider_type = 'LOCAL' THEN
      touches_local := true;
    ELSE
      old_key := account_state_issuer_key(OLD.issuer_uri);
    END IF;
  END IF;
  IF TG_OP <> 'DELETE' THEN
    IF NEW.provider_type = 'LOCAL' THEN
      touches_local := true;
    ELSE
      new_key := account_state_issuer_key(NEW.issuer_uri);
    END IF;
  END IF;
  FOR affected IN
    SELECT id FROM users
     WHERE (touches_local AND issuer = 'urn:opaa:local')
        OR (issuer <> 'urn:opaa:local'
            AND account_state_issuer_key(issuer) IN (old_key, new_key))
     ORDER BY id
  LOOP
    PERFORM account_state_reconcile(affected, 'ACCOUNT_REACTIVATED', false);
  END LOOP;
  RETURN NULL;
END; $$;

CREATE FUNCTION account_state_on_user_change() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', '${flyway:defaultSchema}', 'pg_temp'
    AS $$
BEGIN
  PERFORM account_state_reconcile(NEW.id,
      CASE WHEN OLD.directory_locked_at IS NOT NULL AND NEW.directory_locked_at IS NULL
           THEN 'DIRECTORY_UNLOCKED' ELSE 'ACCOUNT_REACTIVATED' END,
      false);
  RETURN NULL;
END; $$;

CREATE FUNCTION account_state_reconcile(p_user_id uuid, p_reactivation_cause text, p_new_account boolean) RETURNS void
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', '${flyway:defaultSchema}', 'pg_temp'
    AS $$
DECLARE
  account record;
  credentials record;
  provider_enabled boolean;
  local_enabled boolean;
  issuer_key text;
  evaluated_at timestamp with time zone;
  target_locked boolean := false;
  target_cause text;
  open_id uuid;
  open_state text;
  boundary timestamp with time zone;
  locked_as_local boolean;
BEGIN
  -- Row locks serialise every re-evaluation of the account until commit and, unlike an
  -- advisory lock, take no entry of the shared lock table. Every re-evaluation holds
  -- the credentials row, if there is one: a local account that one alone, so that a
  -- change of the credentials never reaches for users; any other account users first,
  -- then the credentials, the order the application writes them in. A handover changes
  -- the issuer and deletes the credentials in one transaction; whoever waited for it
  -- reads the issuer again and locks as the account now is.
  FOR attempt IN 1..3 LOOP
    SELECT issuer INTO account FROM users WHERE id = p_user_id;
    IF NOT FOUND THEN
      RETURN;
    END IF;
    locked_as_local := account.issuer = 'urn:opaa:local';
    IF NOT locked_as_local THEN
      PERFORM 1 FROM users WHERE id = p_user_id FOR NO KEY UPDATE;
    END IF;
    PERFORM 1 FROM local_credentials WHERE user_id = p_user_id FOR NO KEY UPDATE;
    SELECT id, organization_id, issuer, system_role, directory_locked_at, created_at
        INTO account FROM users WHERE id = p_user_id;
    IF NOT FOUND THEN
      RETURN;
    END IF;
    EXIT WHEN (account.issuer = 'urn:opaa:local') = locked_as_local;
  END LOOP;
  IF (account.issuer = 'urn:opaa:local') <> locked_as_local THEN
    RAISE EXCEPTION 'account % kept changing its issuer during its re-evaluation',
        p_user_id;
  END IF;
  evaluated_at := clock_timestamp();

  IF account.directory_locked_at IS NOT NULL THEN
    target_locked := true;
    target_cause := 'DIRECTORY_LOCKED';
  ELSIF account.issuer = 'urn:opaa:local' THEN
    SELECT expires_at, locked_at, locked_reason,
           password_hash IS NOT NULL AS has_password,
           email_verified_at IS NOT NULL AS verified
        INTO credentials FROM local_credentials WHERE user_id = p_user_id;
    IF NOT FOUND THEN
      -- LocalCredentials: a local account always has its row; nothing to evaluate
      RETURN;
    END IF;
    -- a failed-login lockout is passed over: the rule calls the account LOCKED_OUT, the
    -- history records the reasons behind it, if any
    IF credentials.expires_at IS NOT NULL AND credentials.expires_at <= evaluated_at THEN
      target_locked := true;
      target_cause := 'LOCAL_EXPIRED';
    ELSIF credentials.locked_reason IS DISTINCT FROM 'FAILED_LOGINS'
        AND credentials.locked_at IS NOT NULL THEN
      target_locked := true;
      target_cause := CASE WHEN credentials.locked_reason = 'INACTIVITY'
                           THEN 'INACTIVITY_LOCKED' ELSE 'ADMIN_LOCKED' END;
    ELSIF NOT credentials.has_password OR NOT credentials.verified THEN
      target_locked := true;
      target_cause := 'LOCAL_INVITED';
    ELSIF account.system_role <> 'SYSTEM_ADMIN' THEN
      SELECT bool_or(enabled) INTO local_enabled
          FROM oidc_providers WHERE provider_type = 'LOCAL';
      IF NOT coalesce(local_enabled, false) THEN
        target_locked := true;
        target_cause := 'LOCAL_ACCOUNTS_DISABLED';
      END IF;
    END IF;
  ELSE
    issuer_key := account_state_issuer_key(account.issuer);
    -- of two rows of one issuer the last in AccountUsability's order decides; on equal
    -- sort number and name the id, where Java has no fixed order (by hand only)
    SELECT enabled INTO provider_enabled FROM oidc_providers
        WHERE provider_type = 'OIDC' AND account_state_issuer_key(issuer_uri) = issuer_key
        ORDER BY sort_order DESC, display_name DESC, id DESC LIMIT 1;
    IF FOUND THEN
      IF NOT provider_enabled THEN
        target_locked := true;
        target_cause := 'PROVIDER_DISABLED';
      END IF;
    ELSE
      -- no provider of this issuer, deleted or never there. AccountUsability lets the
      -- dev mode's issuer pass without one; the database cannot know it and counts it
      -- unusable - the dev mode is no operating mode
      target_locked := true;
      target_cause := 'PROVIDER_REMOVED';
    END IF;
  END IF;

  SELECT id, state INTO open_id, open_state
      FROM account_state_history WHERE user_id = p_user_id AND valid_to IS NULL;
  IF target_locked = (open_state IS NOT DISTINCT FROM 'LOCKED') THEN
    RETURN;
  END IF;

  boundary := permission_history_next_boundary();
  IF open_id IS NOT NULL THEN
    UPDATE account_state_history SET valid_to = boundary WHERE id = open_id;
  ELSIF NOT p_new_account THEN
    INSERT INTO account_state_history (id, user_id, organization_id, state, cause,
        valid_from, valid_to, created_at)
    VALUES (gen_random_uuid(), p_user_id, account.organization_id, 'ACTIVE',
        'ACCOUNT_CREATED', least(account.created_at, boundary), boundary,
        clock_timestamp());
  END IF;
  INSERT INTO account_state_history (id, user_id, organization_id, state, cause,
      valid_from, created_at)
  VALUES (gen_random_uuid(), p_user_id, account.organization_id,
      CASE WHEN target_locked THEN 'LOCKED' ELSE 'ACTIVE' END,
      CASE WHEN target_locked THEN target_cause ELSE p_reactivation_cause END,
      boundary, clock_timestamp());
END; $$;

CREATE FUNCTION account_state_record_reached_expiries(p_limit integer) RETURNS integer
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', '${flyway:defaultSchema}', 'pg_temp'
    AS $$
DECLARE
  affected uuid;
  recorded integer := 0;
BEGIN
  FOR affected IN
    SELECT c.user_id FROM local_credentials c JOIN users u ON u.id = c.user_id
     WHERE u.issuer = 'urn:opaa:local'
       AND c.expires_at <= clock_timestamp()
       AND NOT EXISTS (
           SELECT 1 FROM account_state_history h
            WHERE h.user_id = c.user_id AND h.valid_to IS NULL AND h.state = 'LOCKED')
     ORDER BY c.user_id
     LIMIT p_limit
  LOOP
    PERFORM account_state_reconcile(affected, 'ACCOUNT_REACTIVATED', false);
    recorded := recorded + 1;
  END LOOP;
  RETURN recorded;
END; $$;

CREATE FUNCTION asset_grants_guard_owner_only() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', '${flyway:defaultSchema}', 'pg_temp'
    AS $$
DECLARE
  owning_user uuid;
BEGIN
  SELECT a.owner_user_id INTO owning_user FROM assets a
    WHERE a.id = NEW.asset_id AND a.owner_only;
  IF FOUND AND NOT ((NEW.subject_type)::text = 'USER'::text
      AND NEW.subject_user_id = owning_user) THEN
    RAISE EXCEPTION 'owner-only asset % admits no grant but its owner''s', NEW.asset_id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END; $$;

CREATE FUNCTION assets_guard_owner_only() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', '${flyway:defaultSchema}', 'pg_temp'
    AS $$
BEGIN
  IF NEW.owner_only IS DISTINCT FROM OLD.owner_only THEN
    RAISE EXCEPTION 'owner_only of asset % is fixed at creation', OLD.id
      USING ERRCODE = 'check_violation';
  END IF;
  IF OLD.owner_only AND (NEW.owner_type IS DISTINCT FROM OLD.owner_type
      OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id
      OR NEW.owner_group_id IS DISTINCT FROM OLD.owner_group_id) THEN
    RAISE EXCEPTION 'the owner of owner-only asset % cannot change', OLD.id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END; $$;

CREATE FUNCTION organizations_seed_capability_grants() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', '${flyway:defaultSchema}', 'pg_temp'
    AS $$
DECLARE
  delivered_capability text;
  delivered_scope text;
  granted_at timestamp with time zone := now();
BEGIN
  FOREACH delivered_capability IN ARRAY ARRAY['CREATE_SPACE', 'CREATE_LIBRARY', 'CREATE_PROMPT_LIBRARY'] LOOP
    INSERT INTO capability_grants (id, organization_id, capability, subject_type, created_at)
    VALUES (gen_random_uuid(), NEW.id, delivered_capability, 'ALL_ACCOUNTS', granted_at);

    INSERT INTO capability_grant_history
      (id, organization_id, capability, subject_type, cause, valid_from, created_at)
    VALUES (gen_random_uuid(), NEW.id, delivered_capability, 'ALL_ACCOUNTS', 'DELIVERED', granted_at, granted_at);
  END LOOP;
  FOREACH delivered_scope IN ARRAY ARRAY['TYPE:FILESYSTEM', 'TYPE:HTTP_DIRECTORY', 'TYPE:RSS_FEED', 'TYPE:CONFLUENCE', 'TYPE:S3'] LOOP
    INSERT INTO capability_grants (id, organization_id, capability, scope, subject_type, created_at)
    VALUES (gen_random_uuid(), NEW.id, 'CREATE_CONNECTOR_LIBRARY', delivered_scope, 'ALL_ACCOUNTS', granted_at);

    INSERT INTO capability_grant_history
      (id, organization_id, capability, scope, subject_type, cause, valid_from, created_at)
    VALUES (gen_random_uuid(), NEW.id, 'CREATE_CONNECTOR_LIBRARY', delivered_scope, 'ALL_ACCOUNTS', 'DELIVERED', granted_at, granted_at);
  END LOOP;
  RETURN NEW;
END; $$;

CREATE FUNCTION permission_history_next_boundary() RETURNS timestamp with time zone
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', '${flyway:defaultSchema}', 'pg_temp'
    AS $$
DECLARE
  reading bigint;
  boundary bigint;
BEGIN
  PERFORM pg_advisory_lock(230, 0);
  BEGIN
    reading := floor(extract(epoch FROM clock_timestamp()) * 1000000)::bigint;
    boundary := nextval('permission_history_boundary_seq');
    IF boundary < reading THEN
      boundary := setval('permission_history_boundary_seq', reading);
    END IF;
  EXCEPTION WHEN OTHERS OR query_canceled THEN
    PERFORM pg_advisory_unlock(230, 0);
    RAISE;
  END;
  PERFORM pg_advisory_unlock(230, 0);
  RETURN timestamp with time zone 'epoch' + boundary * interval '1 microsecond';
END; $$;



SET default_tablespace = '';

SET default_table_access_method = heap;

CREATE TABLE account_state_history (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    state character varying(20) NOT NULL,
    cause character varying(30) NOT NULL,
    valid_from timestamp with time zone NOT NULL,
    valid_to timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_account_state_history_cause CHECK ((cause IN ('ACCOUNT_CREATED', 'DIRECTORY_LOCKED', 'DIRECTORY_UNLOCKED', 'ADMIN_LOCKED', 'INACTIVITY_LOCKED', 'LOCAL_EXPIRED', 'LOCAL_INVITED', 'LOCAL_ACCOUNTS_DISABLED', 'PROVIDER_DISABLED', 'PROVIDER_REMOVED', 'ACCOUNT_REACTIVATED'))),
    CONSTRAINT chk_account_state_history_state CHECK ((state IN ('ACTIVE', 'LOCKED')))
);

CREATE TABLE asset_favorites (
    asset_id uuid NOT NULL,
    user_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE asset_grant_history (
    id uuid NOT NULL,
    asset_id uuid CONSTRAINT asset_grant_history_library_id_not_null NOT NULL,
    organization_id uuid NOT NULL,
    subject_type character varying(20) NOT NULL,
    subject_user_id uuid,
    subject_group_id uuid,
    role character varying(20) NOT NULL,
    expires_at timestamp with time zone,
    cause character varying(30) NOT NULL,
    actor_user_id uuid,
    valid_from timestamp with time zone NOT NULL,
    valid_to timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    asset_type character varying(30) NOT NULL,
    transfer_id uuid,
    CONSTRAINT chk_asset_grant_history_asset_type_format CHECK (((asset_type)::text ~ '^[A-Z][A-Z0-9_]*$'::text)),
    CONSTRAINT chk_asset_grant_history_cause CHECK ((cause IN ('GRANTED', 'ROLE_CHANGED', 'REVOKED', 'BACKFILL', 'ASSET_DELETED', 'TRANSFERRED_IN', 'TRANSFERRED_OUT'))),
    CONSTRAINT chk_asset_grant_history_role CHECK ((role IN ('VIEWER', 'EDITOR', 'MANAGER', 'OWNER'))),
    CONSTRAINT chk_asset_grant_history_subject CHECK (((((subject_type)::text = 'USER'::text) AND (subject_user_id IS NOT NULL) AND (subject_group_id IS NULL)) OR (((subject_type)::text = 'GROUP'::text) AND (subject_group_id IS NOT NULL) AND (subject_user_id IS NULL)) OR (((subject_type)::text = 'ALL_ACCOUNTS'::text) AND (subject_user_id IS NULL) AND (subject_group_id IS NULL)))),
    CONSTRAINT chk_asset_grant_history_subject_type CHECK ((subject_type IN ('USER', 'GROUP', 'ALL_ACCOUNTS')))
);

CREATE TABLE asset_grants (
    id uuid NOT NULL,
    asset_id uuid CONSTRAINT asset_grants_library_id_not_null NOT NULL,
    organization_id uuid NOT NULL,
    subject_type character varying(20) NOT NULL,
    subject_user_id uuid,
    subject_group_id uuid,
    role character varying(20) NOT NULL,
    expires_at timestamp with time zone,
    granted_by_user_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    asset_type character varying(30) NOT NULL,
    CONSTRAINT chk_asset_grants_asset_type_format CHECK (((asset_type)::text ~ '^[A-Z][A-Z0-9_]*$'::text)),
    CONSTRAINT chk_asset_grants_role CHECK ((role IN ('VIEWER', 'EDITOR', 'MANAGER', 'OWNER'))),
    CONSTRAINT chk_asset_grants_subject CHECK (((((subject_type)::text = 'USER'::text) AND (subject_user_id IS NOT NULL) AND (subject_group_id IS NULL)) OR (((subject_type)::text = 'GROUP'::text) AND (subject_group_id IS NOT NULL) AND (subject_user_id IS NULL)) OR (((subject_type)::text = 'ALL_ACCOUNTS'::text) AND (subject_user_id IS NULL) AND (subject_group_id IS NULL)))),
    CONSTRAINT chk_asset_grants_subject_type CHECK ((subject_type IN ('USER', 'GROUP', 'ALL_ACCOUNTS')))
);

CREATE TABLE asset_ownership_history (
    id uuid NOT NULL,
    asset_type character varying(30) NOT NULL,
    asset_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    owner_type character varying(20) NOT NULL,
    owner_user_id uuid,
    owner_group_id uuid,
    cause character varying(30) NOT NULL,
    actor_user_id uuid,
    valid_from timestamp with time zone NOT NULL,
    valid_to timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    transfer_id uuid,
    CONSTRAINT chk_asset_ownership_history_asset_type_format CHECK (((asset_type)::text ~ '^[A-Z][A-Z0-9_]*$'::text)),
    CONSTRAINT chk_asset_ownership_history_cause CHECK ((cause IN ('CREATED', 'TRANSFERRED', 'BACKFILL', 'ASSET_DELETED'))),
    CONSTRAINT chk_asset_ownership_history_owner CHECK (((((owner_type)::text = 'USER'::text) AND (owner_user_id IS NOT NULL) AND (owner_group_id IS NULL)) OR (((owner_type)::text = 'GROUP'::text) AND (owner_group_id IS NOT NULL) AND (owner_user_id IS NULL)))),
    CONSTRAINT chk_asset_ownership_history_owner_type CHECK ((owner_type IN ('USER', 'GROUP')))
);

CREATE TABLE asset_visibility_history (
    id uuid CONSTRAINT library_visibility_history_id_not_null NOT NULL,
    asset_id uuid CONSTRAINT library_visibility_history_library_id_not_null NOT NULL,
    organization_id uuid CONSTRAINT library_visibility_history_organization_id_not_null NOT NULL,
    cause character varying(30) CONSTRAINT library_visibility_history_cause_not_null NOT NULL,
    actor_user_id uuid,
    valid_from timestamp with time zone CONSTRAINT library_visibility_history_valid_from_not_null NOT NULL,
    valid_to timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT library_visibility_history_created_at_not_null NOT NULL,
    external_access_state character varying(20) DEFAULT 'NEVER_SET'::character varying CONSTRAINT library_visibility_history_external_access_state_not_null NOT NULL,
    external_access_expires_at timestamp with time zone,
    asset_type character varying(30) NOT NULL,
    CONSTRAINT chk_asset_visibility_history_asset_type_format CHECK (((asset_type)::text ~ '^[A-Z][A-Z0-9_]*$'::text)),
    CONSTRAINT chk_asset_visibility_history_cause CHECK ((cause IN ('CREATED', 'BACKFILL', 'ASSET_DELETED', 'EXTERNAL_ACCESS_CHANGED', 'EXTERNAL_ACCESS_EXPIRED'))),
    CONSTRAINT chk_asset_visibility_history_external_access_state CHECK ((external_access_state IN ('NEVER_SET', 'ACTIVE', 'WITHDRAWN', 'EXPIRED', 'SUSPENDED')))
);

CREATE TABLE assets (
    id uuid NOT NULL,
    asset_type character varying(30) NOT NULL,
    organization_id uuid NOT NULL,
    name character varying(255) NOT NULL,
    description character varying(2000),
    owner_type character varying(20) NOT NULL,
    owner_user_id uuid,
    owner_group_id uuid,
    origin character varying(20) DEFAULT 'LOCAL'::character varying NOT NULL,
    created_by_user_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    owner_only boolean DEFAULT false NOT NULL,
    CONSTRAINT chk_assets_asset_type_format CHECK (((asset_type)::text ~ '^[A-Z][A-Z0-9_]*$'::text)),
    CONSTRAINT chk_assets_origin CHECK ((origin IN ('LOCAL', 'BUILT_IN'))),
    CONSTRAINT chk_assets_owner CHECK (((((owner_type)::text = 'USER'::text) AND (owner_user_id IS NOT NULL) AND (owner_group_id IS NULL)) OR (((owner_type)::text = 'GROUP'::text) AND (owner_group_id IS NOT NULL) AND (owner_user_id IS NULL)))),
    CONSTRAINT chk_assets_owner_only CHECK (((NOT owner_only) OR ((owner_type)::text = 'USER'::text))),
    CONSTRAINT chk_assets_owner_type CHECK ((owner_type IN ('USER', 'GROUP')))
);

CREATE TABLE capability_grant_history (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    capability character varying(40) NOT NULL,
    subject_type character varying(20) NOT NULL,
    subject_user_id uuid,
    subject_group_id uuid,
    cause character varying(30) NOT NULL,
    actor_user_id uuid,
    valid_from timestamp with time zone NOT NULL,
    valid_to timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    transfer_id uuid,
    scope character varying(200),
    CONSTRAINT chk_capability_grant_history_capability CHECK ((capability IN ('CREATE_SPACE', 'CREATE_LIBRARY', 'CREATE_CONNECTOR_LIBRARY', 'CREATE_INTERNAL_GROUP', 'CREATE_PROMPT_LIBRARY'))),
    CONSTRAINT chk_capability_grant_history_cause CHECK ((cause IN ('DELIVERED', 'GRANTED', 'REVOKED', 'TRANSFERRED_IN', 'TRANSFERRED_OUT'))),
    CONSTRAINT chk_capability_grant_history_interval CHECK (((valid_to IS NULL) OR (valid_to >= valid_from))),
    CONSTRAINT chk_capability_grant_history_scope CHECK ((((capability)::text = 'CREATE_CONNECTOR_LIBRARY'::text) = (scope IS NOT NULL))),
    CONSTRAINT chk_capability_grant_history_subject CHECK (((((subject_type)::text = 'USER'::text) AND (subject_user_id IS NOT NULL) AND (subject_group_id IS NULL)) OR (((subject_type)::text = 'GROUP'::text) AND (subject_group_id IS NOT NULL) AND (subject_user_id IS NULL)) OR (((subject_type)::text = 'ALL_ACCOUNTS'::text) AND (subject_user_id IS NULL) AND (subject_group_id IS NULL)))),
    CONSTRAINT chk_capability_grant_history_subject_type CHECK ((subject_type IN ('USER', 'GROUP', 'ALL_ACCOUNTS')))
);

CREATE TABLE capability_grants (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    capability character varying(40) NOT NULL,
    subject_type character varying(20) NOT NULL,
    subject_user_id uuid,
    subject_group_id uuid,
    granted_by_user_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    scope character varying(200),
    CONSTRAINT chk_capability_grants_capability CHECK ((capability IN ('CREATE_SPACE', 'CREATE_LIBRARY', 'CREATE_CONNECTOR_LIBRARY', 'CREATE_INTERNAL_GROUP', 'CREATE_PROMPT_LIBRARY'))),
    CONSTRAINT chk_capability_grants_scope CHECK ((((capability)::text = 'CREATE_CONNECTOR_LIBRARY'::text) = (scope IS NOT NULL))),
    CONSTRAINT chk_capability_grants_subject CHECK (((((subject_type)::text = 'USER'::text) AND (subject_user_id IS NOT NULL) AND (subject_group_id IS NULL)) OR (((subject_type)::text = 'GROUP'::text) AND (subject_group_id IS NOT NULL) AND (subject_user_id IS NULL)) OR (((subject_type)::text = 'ALL_ACCOUNTS'::text) AND (subject_user_id IS NULL) AND (subject_group_id IS NULL)))),
    CONSTRAINT chk_capability_grants_subject_type CHECK ((subject_type IN ('USER', 'GROUP', 'ALL_ACCOUNTS')))
);

CREATE TABLE directory_connectors (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    provider_id uuid NOT NULL,
    connector_type character varying(20) NOT NULL,
    base_url character varying(500),
    client_id character varying(255) NOT NULL,
    client_secret character varying(2000) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT chk_directory_connectors_secret_encrypted CHECK (((client_secret)::text ~~ 'enc:%'::text)),
    CONSTRAINT chk_directory_connectors_type CHECK (((connector_type)::text = 'KEYCLOAK'::text))
);

CREATE TABLE directory_sync_pending_plans (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    provider_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    fingerprint character varying(64) NOT NULL,
    changed_fraction double precision NOT NULL,
    memberships_removed integer NOT NULL,
    report text NOT NULL,
    accounts_locked integer DEFAULT 0 NOT NULL
);

CREATE TABLE directory_sync_status (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    last_run_at timestamp with time zone NOT NULL,
    last_outcome character varying(30) NOT NULL,
    last_message character varying(2000),
    last_changed_fraction double precision,
    last_applied_at timestamp with time zone,
    provider_id uuid NOT NULL
);

CREATE TABLE group_membership_history (
    id uuid NOT NULL,
    group_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    user_id uuid NOT NULL,
    cause character varying(30) NOT NULL,
    actor_user_id uuid,
    valid_from timestamp with time zone NOT NULL,
    valid_to timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_group_membership_history_cause CHECK ((cause IN ('ADDED', 'REMOVED', 'DIRECTORY_SYNC_ADDED', 'DIRECTORY_SYNC_REMOVED', 'BACKFILL', 'GROUP_DELETED', 'IDENTITY_PROVIDER_ADDED', 'IDENTITY_PROVIDER_REMOVED', 'GROUP_DEACTIVATED', 'GROUP_REACTIVATED')))
);

CREATE TABLE group_memberships (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    group_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE group_stewards (
    id uuid NOT NULL,
    group_id uuid NOT NULL,
    user_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    appointed_by_user_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE groups (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    kind character varying(20) NOT NULL,
    name character varying(255) NOT NULL,
    description character varying(2000),
    external_id character varying(255),
    parent_group_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    dissolved boolean DEFAULT false NOT NULL,
    dissolved_at timestamp with time zone,
    provider_id uuid,
    source_path character varying(1000),
    released_for_use boolean DEFAULT false NOT NULL,
    protected boolean DEFAULT false NOT NULL,
    deactivated boolean DEFAULT false NOT NULL,
    deactivated_at timestamp with time zone,
    CONSTRAINT chk_groups_deactivated_at CHECK ((deactivated = (deactivated_at IS NOT NULL))),
    CONSTRAINT chk_groups_deactivated_internal CHECK (((NOT deactivated) OR ((kind)::text = 'AD_HOC'::text))),
    CONSTRAINT chk_groups_kind CHECK ((kind IN ('ORG_UNIT', 'AD_HOC', 'IDENTITY_PROVIDER'))),
    CONSTRAINT chk_groups_provider_kind CHECK (((((kind)::text = 'AD_HOC'::text) AND (provider_id IS NULL)) OR (((kind)::text <> 'AD_HOC'::text) AND (provider_id IS NOT NULL))))
);

CREATE SEQUENCE permission_history_boundary_seq
    START WITH 0
    INCREMENT BY 1
    MINVALUE 0
    NO MAXVALUE
    CACHE 1;

CREATE TABLE permission_history_retention_settings (
    id integer DEFAULT 1 NOT NULL,
    retention_months integer NOT NULL,
    last_cutoff timestamp with time zone,
    updated_at timestamp with time zone NOT NULL,
    account_state_complete_since timestamp with time zone,
    CONSTRAINT chk_permission_history_retention_months CHECK (((retention_months >= 12) AND (retention_months <= 120))),
    CONSTRAINT chk_permission_history_retention_singleton CHECK ((id = 1))
);

CREATE TABLE permission_transfer_objects (
    id uuid NOT NULL,
    transfer_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    asset_type character varying(30) NOT NULL,
    asset_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_permission_transfer_objects_asset_type_format CHECK (((asset_type)::text ~ '^[A-Z][A-Z0-9_]*$'::text))
);

CREATE TABLE permission_transfers (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    source_type character varying(20) NOT NULL,
    source_user_id uuid,
    source_group_id uuid,
    source_label text,
    target_type character varying(20) NOT NULL,
    target_user_id uuid,
    target_group_id uuid,
    target_label text,
    scope character varying(200) NOT NULL,
    grant_count integer DEFAULT 0 NOT NULL,
    space_membership_count integer DEFAULT 0 NOT NULL,
    capability_count integer DEFAULT 0 NOT NULL,
    ownership_count integer DEFAULT 0 NOT NULL,
    stewardship_count integer DEFAULT 0 NOT NULL,
    actor_user_id uuid,
    performed_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_permission_transfers_scope CHECK (((scope)::text ~ '^[A-Z][A-Z_]*(,[A-Z][A-Z_]*)*$'::text)),
    CONSTRAINT chk_permission_transfers_source CHECK (((((source_type)::text = 'USER'::text) AND (source_user_id IS NOT NULL) AND (source_group_id IS NULL)) OR (((source_type)::text = 'GROUP'::text) AND (source_group_id IS NOT NULL) AND (source_user_id IS NULL)))),
    CONSTRAINT chk_permission_transfers_source_label CHECK ((((source_type)::text = 'GROUP'::text) OR (source_label IS NULL))),
    CONSTRAINT chk_permission_transfers_source_type CHECK ((source_type IN ('USER', 'GROUP'))),
    CONSTRAINT chk_permission_transfers_target CHECK (((((target_type)::text = 'USER'::text) AND (target_user_id IS NOT NULL) AND (target_group_id IS NULL)) OR (((target_type)::text = 'GROUP'::text) AND (target_group_id IS NOT NULL) AND (target_user_id IS NULL)))),
    CONSTRAINT chk_permission_transfers_target_label CHECK ((((target_type)::text = 'GROUP'::text) OR (target_label IS NULL))),
    CONSTRAINT chk_permission_transfers_target_type CHECK ((target_type IN ('USER', 'GROUP')))
);

CREATE TABLE succession_cases (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    kind character varying(40) NOT NULL,
    object_type character varying(30) NOT NULL,
    object_id uuid NOT NULL,
    first_seen_at timestamp with time zone NOT NULL,
    last_seen_at timestamp with time zone NOT NULL,
    closed_at timestamp with time zone,
    closed_by_user_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    asset_type character varying(30),
    CONSTRAINT chk_succession_cases_asset_type CHECK ((((object_type)::text = 'ASSET'::text) = (asset_type IS NOT NULL))),
    CONSTRAINT chk_succession_cases_closed CHECK (((closed_at IS NOT NULL) OR (closed_by_user_id IS NULL))),
    CONSTRAINT chk_succession_cases_kind CHECK ((kind IN ('OPEN_SUCCESSION', 'GRANTS_WITHOUT_RECIPIENT', 'GROUP_WITHOUT_EFFECT'))),
    CONSTRAINT chk_succession_cases_object_type CHECK ((object_type IN ('ASSET', 'SPACE', 'GROUP')))
);

ALTER TABLE ONLY account_state_history
    ADD CONSTRAINT account_state_history_pkey PRIMARY KEY (id);

ALTER TABLE ONLY asset_favorites
    ADD CONSTRAINT asset_favorites_pkey PRIMARY KEY (asset_id, user_id);

ALTER TABLE ONLY asset_grant_history
    ADD CONSTRAINT asset_grant_history_pkey PRIMARY KEY (id);

ALTER TABLE ONLY asset_grants
    ADD CONSTRAINT asset_grants_pkey PRIMARY KEY (id);

ALTER TABLE ONLY asset_ownership_history
    ADD CONSTRAINT asset_ownership_history_pkey PRIMARY KEY (id);

ALTER TABLE ONLY asset_visibility_history
    ADD CONSTRAINT asset_visibility_history_pkey PRIMARY KEY (id);

ALTER TABLE ONLY assets
    ADD CONSTRAINT assets_pkey PRIMARY KEY (id);

ALTER TABLE ONLY capability_grant_history
    ADD CONSTRAINT capability_grant_history_pkey PRIMARY KEY (id);

ALTER TABLE ONLY capability_grants
    ADD CONSTRAINT capability_grants_pkey PRIMARY KEY (id);

ALTER TABLE ONLY directory_connectors
    ADD CONSTRAINT directory_connectors_pkey PRIMARY KEY (id);

ALTER TABLE ONLY directory_sync_pending_plans
    ADD CONSTRAINT directory_sync_pending_plans_pkey PRIMARY KEY (id);

ALTER TABLE ONLY directory_sync_status
    ADD CONSTRAINT directory_sync_status_pkey PRIMARY KEY (id);

ALTER TABLE ONLY group_membership_history
    ADD CONSTRAINT group_membership_history_pkey PRIMARY KEY (id);

ALTER TABLE ONLY group_memberships
    ADD CONSTRAINT group_memberships_pkey PRIMARY KEY (id);

ALTER TABLE ONLY group_stewards
    ADD CONSTRAINT group_stewards_pkey PRIMARY KEY (id);

ALTER TABLE ONLY groups
    ADD CONSTRAINT groups_pkey PRIMARY KEY (id);

ALTER TABLE ONLY permission_history_retention_settings
    ADD CONSTRAINT permission_history_retention_settings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY permission_transfer_objects
    ADD CONSTRAINT permission_transfer_objects_pkey PRIMARY KEY (id);

ALTER TABLE ONLY permission_transfers
    ADD CONSTRAINT permission_transfers_pkey PRIMARY KEY (id);

ALTER TABLE ONLY succession_cases
    ADD CONSTRAINT succession_cases_pkey PRIMARY KEY (id);

ALTER TABLE ONLY assets
    ADD CONSTRAINT uk_assets_id_organization UNIQUE (id, organization_id);

ALTER TABLE ONLY assets
    ADD CONSTRAINT uk_assets_id_type_organization UNIQUE (id, asset_type, organization_id);

ALTER TABLE ONLY directory_connectors
    ADD CONSTRAINT uk_directory_connectors_provider UNIQUE (provider_id);

ALTER TABLE ONLY directory_sync_pending_plans
    ADD CONSTRAINT uk_directory_sync_pending_plans_provider UNIQUE (provider_id);

ALTER TABLE ONLY directory_sync_status
    ADD CONSTRAINT uk_directory_sync_status_organization_provider UNIQUE (organization_id, provider_id);

ALTER TABLE ONLY group_memberships
    ADD CONSTRAINT uk_group_memberships_user_group UNIQUE (user_id, group_id);

ALTER TABLE ONLY groups
    ADD CONSTRAINT uk_groups_id_organization UNIQUE (id, organization_id);

ALTER TABLE ONLY permission_transfers
    ADD CONSTRAINT uk_permission_transfers_id_organization UNIQUE (id, organization_id);

ALTER TABLE ONLY succession_cases
    ADD CONSTRAINT uk_succession_cases_id_organization UNIQUE (id, organization_id);

CREATE INDEX idx_account_state_history_user ON account_state_history USING btree (user_id, organization_id, valid_from);

CREATE INDEX idx_account_state_history_valid_to ON account_state_history USING btree (valid_to) WHERE (valid_to IS NOT NULL);

CREATE INDEX idx_asset_favorites_user ON asset_favorites USING btree (user_id);

CREATE INDEX idx_asset_grant_history_all_accounts ON asset_grant_history USING btree (asset_type, organization_id, valid_from) WHERE ((subject_type)::text = 'ALL_ACCOUNTS'::text);

CREATE INDEX idx_asset_grant_history_asset ON asset_grant_history USING btree (asset_type, asset_id);

CREATE INDEX idx_asset_grant_history_subject_group_organization ON asset_grant_history USING btree (subject_group_id, organization_id, valid_from) WHERE ((subject_type)::text = 'GROUP'::text);

CREATE INDEX idx_asset_grant_history_subject_user_organization ON asset_grant_history USING btree (subject_user_id, organization_id, valid_from) WHERE ((subject_type)::text = 'USER'::text);

CREATE INDEX idx_asset_grant_history_transfer ON asset_grant_history USING btree (transfer_id) WHERE (transfer_id IS NOT NULL);

CREATE INDEX idx_asset_grants_all_accounts ON asset_grants USING btree (asset_type, organization_id) WHERE ((subject_type)::text = 'ALL_ACCOUNTS'::text);

CREATE INDEX idx_asset_grants_subject_group_organization ON asset_grants USING btree (subject_group_id, organization_id) WHERE ((subject_type)::text = 'GROUP'::text);

CREATE INDEX idx_asset_grants_subject_user_organization ON asset_grants USING btree (subject_user_id, organization_id) WHERE ((subject_type)::text = 'USER'::text);

CREATE INDEX idx_asset_ownership_history_asset ON asset_ownership_history USING btree (asset_type, asset_id);

CREATE INDEX idx_asset_ownership_history_owner_group_organization ON asset_ownership_history USING btree (owner_group_id, organization_id, valid_from) WHERE ((owner_type)::text = 'GROUP'::text);

CREATE INDEX idx_asset_ownership_history_owner_user_organization ON asset_ownership_history USING btree (owner_user_id, organization_id, valid_from) WHERE ((owner_type)::text = 'USER'::text);

CREATE INDEX idx_asset_ownership_history_transfer ON asset_ownership_history USING btree (transfer_id) WHERE (transfer_id IS NOT NULL);

CREATE INDEX idx_asset_visibility_history_asset ON asset_visibility_history USING btree (asset_type, asset_id);

CREATE INDEX idx_asset_visibility_history_organization ON asset_visibility_history USING btree (organization_id, valid_from);

CREATE INDEX idx_assets_organization_type ON assets USING btree (organization_id, asset_type);

CREATE INDEX idx_assets_owner_group ON assets USING btree (owner_group_id, organization_id) WHERE ((owner_type)::text = 'GROUP'::text);

CREATE INDEX idx_assets_owner_user ON assets USING btree (owner_user_id, organization_id) WHERE ((owner_type)::text = 'USER'::text);

CREATE INDEX idx_capability_grant_history_lookup ON capability_grant_history USING btree (organization_id, capability, valid_from);

CREATE INDEX idx_capability_grant_history_subject_user ON capability_grant_history USING btree (subject_user_id, organization_id, valid_from) WHERE ((subject_type)::text = 'USER'::text);

CREATE INDEX idx_capability_grant_history_transfer ON capability_grant_history USING btree (transfer_id) WHERE (transfer_id IS NOT NULL);

CREATE INDEX idx_capability_grants_organization_capability ON capability_grants USING btree (organization_id, capability);

CREATE INDEX idx_group_membership_history_group ON group_membership_history USING btree (group_id);

CREATE INDEX idx_group_membership_history_user_organization ON group_membership_history USING btree (user_id, organization_id, valid_from);

CREATE INDEX idx_group_stewards_user ON group_stewards USING btree (user_id);

CREATE INDEX idx_groups_provider ON groups USING btree (provider_id) WHERE (provider_id IS NOT NULL);

CREATE INDEX idx_permission_transfer_objects_asset ON permission_transfer_objects USING btree (asset_type, asset_id);

CREATE INDEX idx_permission_transfers_organization_performed ON permission_transfers USING btree (organization_id, performed_at);

CREATE INDEX idx_permission_transfers_source_group ON permission_transfers USING btree (source_group_id) WHERE ((source_type)::text = 'GROUP'::text);

CREATE INDEX idx_succession_cases_organization_open ON succession_cases USING btree (organization_id, kind, first_seen_at) WHERE (closed_at IS NULL);

CREATE UNIQUE INDEX uk_account_state_history_open ON account_state_history USING btree (user_id) WHERE (valid_to IS NULL);

CREATE UNIQUE INDEX uk_asset_grant_history_open_all_accounts ON asset_grant_history USING btree (asset_type, asset_id) WHERE (((subject_type)::text = 'ALL_ACCOUNTS'::text) AND (valid_to IS NULL));

CREATE UNIQUE INDEX uk_asset_grant_history_open_group ON asset_grant_history USING btree (asset_type, asset_id, subject_group_id) WHERE (((subject_type)::text = 'GROUP'::text) AND (valid_to IS NULL));

CREATE UNIQUE INDEX uk_asset_grant_history_open_user ON asset_grant_history USING btree (asset_type, asset_id, subject_user_id) WHERE (((subject_type)::text = 'USER'::text) AND (valid_to IS NULL));

CREATE UNIQUE INDEX uk_asset_grants_all_accounts ON asset_grants USING btree (asset_type, asset_id) WHERE ((subject_type)::text = 'ALL_ACCOUNTS'::text);

CREATE UNIQUE INDEX uk_asset_grants_group_subject ON asset_grants USING btree (asset_type, asset_id, subject_group_id) WHERE ((subject_type)::text = 'GROUP'::text);

CREATE UNIQUE INDEX uk_asset_grants_user_subject ON asset_grants USING btree (asset_type, asset_id, subject_user_id) WHERE ((subject_type)::text = 'USER'::text);

CREATE UNIQUE INDEX uk_asset_ownership_history_open ON asset_ownership_history USING btree (asset_type, asset_id) WHERE (valid_to IS NULL);

CREATE UNIQUE INDEX uk_asset_visibility_history_open ON asset_visibility_history USING btree (asset_type, asset_id) WHERE (valid_to IS NULL);

CREATE UNIQUE INDEX uk_capability_grant_history_open_all_accounts ON capability_grant_history USING btree (organization_id, capability, scope) NULLS NOT DISTINCT WHERE (((subject_type)::text = 'ALL_ACCOUNTS'::text) AND (valid_to IS NULL));

CREATE UNIQUE INDEX uk_capability_grant_history_open_group ON capability_grant_history USING btree (organization_id, capability, scope, subject_group_id) NULLS NOT DISTINCT WHERE (((subject_type)::text = 'GROUP'::text) AND (valid_to IS NULL));

CREATE UNIQUE INDEX uk_capability_grant_history_open_user ON capability_grant_history USING btree (organization_id, capability, scope, subject_user_id) NULLS NOT DISTINCT WHERE (((subject_type)::text = 'USER'::text) AND (valid_to IS NULL));

CREATE UNIQUE INDEX uk_capability_grants_all_accounts ON capability_grants USING btree (organization_id, capability, scope) NULLS NOT DISTINCT WHERE ((subject_type)::text = 'ALL_ACCOUNTS'::text);

CREATE UNIQUE INDEX uk_capability_grants_group_subject ON capability_grants USING btree (organization_id, capability, scope, subject_group_id) NULLS NOT DISTINCT WHERE ((subject_type)::text = 'GROUP'::text);

CREATE UNIQUE INDEX uk_capability_grants_user_subject ON capability_grants USING btree (organization_id, capability, scope, subject_user_id) NULLS NOT DISTINCT WHERE ((subject_type)::text = 'USER'::text);

CREATE UNIQUE INDEX uk_group_membership_history_open ON group_membership_history USING btree (group_id, user_id) WHERE (valid_to IS NULL);

CREATE UNIQUE INDEX uk_group_stewards_group_user ON group_stewards USING btree (group_id, user_id);

CREATE UNIQUE INDEX uk_groups_organization_provider_kind_external_id ON groups USING btree (organization_id, provider_id, kind, external_id) NULLS NOT DISTINCT WHERE (external_id IS NOT NULL);

CREATE UNIQUE INDEX uk_permission_transfer_objects ON permission_transfer_objects USING btree (transfer_id, asset_type, asset_id);

CREATE UNIQUE INDEX uk_succession_cases_open ON succession_cases USING btree (kind, object_type, object_id) WHERE (closed_at IS NULL);

CREATE TRIGGER trg_asset_grants_guard_owner_only BEFORE INSERT OR UPDATE ON asset_grants FOR EACH ROW EXECUTE FUNCTION asset_grants_guard_owner_only();

CREATE TRIGGER trg_asset_grants_invalidation AFTER INSERT OR DELETE OR UPDATE OR TRUNCATE ON asset_grants FOR EACH STATEMENT EXECUTE FUNCTION notify_cache_invalidation('rights');

CREATE TRIGGER trg_assets_guard_owner_only BEFORE UPDATE ON assets FOR EACH ROW EXECUTE FUNCTION assets_guard_owner_only();

CREATE TRIGGER trg_group_memberships_invalidation AFTER INSERT OR DELETE OR UPDATE OR TRUNCATE ON group_memberships FOR EACH STATEMENT EXECUTE FUNCTION notify_cache_invalidation('rights');

CREATE TRIGGER trg_groups_invalidation AFTER INSERT OR DELETE OR UPDATE OR TRUNCATE ON groups FOR EACH STATEMENT EXECUTE FUNCTION notify_cache_invalidation('rights');

CREATE TRIGGER trg_local_credentials_account_state_insert AFTER INSERT ON local_credentials FOR EACH ROW EXECUTE FUNCTION account_state_on_credentials_change();

CREATE TRIGGER trg_local_credentials_account_state_update AFTER UPDATE ON local_credentials FOR EACH ROW WHEN (((old.expires_at IS DISTINCT FROM new.expires_at) OR (old.locked_at IS DISTINCT FROM new.locked_at) OR ((old.locked_reason)::text IS DISTINCT FROM (new.locked_reason)::text) OR ((old.password_hash IS NULL) <> (new.password_hash IS NULL)) OR ((old.email_verified_at IS NULL) <> (new.email_verified_at IS NULL)))) EXECUTE FUNCTION account_state_on_credentials_change();

CREATE TRIGGER trg_oidc_providers_account_state_change AFTER UPDATE ON oidc_providers FOR EACH ROW WHEN (((old.enabled IS DISTINCT FROM new.enabled) OR ((old.issuer_uri)::text IS DISTINCT FROM (new.issuer_uri)::text) OR ((old.provider_type)::text IS DISTINCT FROM (new.provider_type)::text) OR (old.sort_order IS DISTINCT FROM new.sort_order) OR ((old.display_name)::text IS DISTINCT FROM (new.display_name)::text))) EXECUTE FUNCTION account_state_on_provider_change();

CREATE TRIGGER trg_oidc_providers_account_state_presence AFTER INSERT OR DELETE ON oidc_providers FOR EACH ROW EXECUTE FUNCTION account_state_on_provider_change();

CREATE TRIGGER trg_organizations_seed_capability_grants AFTER INSERT ON organizations FOR EACH ROW EXECUTE FUNCTION organizations_seed_capability_grants();

CREATE TRIGGER trg_users_account_state AFTER UPDATE ON users FOR EACH ROW WHEN (((old.directory_locked_at IS DISTINCT FROM new.directory_locked_at) OR ((old.issuer)::text IS DISTINCT FROM (new.issuer)::text) OR (((old.system_role)::text IS DISTINCT FROM (new.system_role)::text) AND ((new.issuer)::text = 'urn:opaa:local'::text)))) EXECUTE FUNCTION account_state_on_user_change();

ALTER TABLE ONLY account_state_history
    ADD CONSTRAINT fk_account_state_history_user_organization FOREIGN KEY (user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE CASCADE;

ALTER TABLE ONLY asset_favorites
    ADD CONSTRAINT fk_asset_favorites_asset_organization FOREIGN KEY (asset_id, organization_id) REFERENCES assets(id, organization_id) ON DELETE CASCADE;

ALTER TABLE ONLY asset_favorites
    ADD CONSTRAINT fk_asset_favorites_user_organization FOREIGN KEY (user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE CASCADE;

ALTER TABLE ONLY asset_grant_history
    ADD CONSTRAINT fk_asset_grant_history_actor_user_organization FOREIGN KEY (actor_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE SET NULL (actor_user_id);

ALTER TABLE ONLY asset_grant_history
    ADD CONSTRAINT fk_asset_grant_history_subject_user_organization FOREIGN KEY (subject_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE ONLY asset_grant_history
    ADD CONSTRAINT fk_asset_grant_history_transfer FOREIGN KEY (transfer_id, organization_id) REFERENCES permission_transfers(id, organization_id) ON DELETE SET NULL (transfer_id);

ALTER TABLE ONLY asset_grants
    ADD CONSTRAINT fk_asset_grants_asset_organization FOREIGN KEY (asset_id, asset_type, organization_id) REFERENCES assets(id, asset_type, organization_id) ON DELETE CASCADE;

ALTER TABLE ONLY asset_grants
    ADD CONSTRAINT fk_asset_grants_granted_by_user_organization FOREIGN KEY (granted_by_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE ONLY asset_grants
    ADD CONSTRAINT fk_asset_grants_subject_group_organization FOREIGN KEY (subject_group_id, organization_id) REFERENCES groups(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE ONLY asset_grants
    ADD CONSTRAINT fk_asset_grants_subject_user_organization FOREIGN KEY (subject_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE ONLY asset_ownership_history
    ADD CONSTRAINT fk_asset_ownership_history_actor_user_organization FOREIGN KEY (actor_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE SET NULL (actor_user_id);

ALTER TABLE ONLY asset_ownership_history
    ADD CONSTRAINT fk_asset_ownership_history_owner_user_organization FOREIGN KEY (owner_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE ONLY asset_ownership_history
    ADD CONSTRAINT fk_asset_ownership_history_transfer FOREIGN KEY (transfer_id, organization_id) REFERENCES permission_transfers(id, organization_id) ON DELETE SET NULL (transfer_id);

ALTER TABLE ONLY asset_visibility_history
    ADD CONSTRAINT fk_asset_visibility_history_actor_user_organization FOREIGN KEY (actor_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE SET NULL (actor_user_id);

ALTER TABLE ONLY assets
    ADD CONSTRAINT fk_assets_created_by_user_organization FOREIGN KEY (created_by_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE SET NULL (created_by_user_id);

ALTER TABLE ONLY assets
    ADD CONSTRAINT fk_assets_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT;

ALTER TABLE ONLY assets
    ADD CONSTRAINT fk_assets_owner_group_organization FOREIGN KEY (owner_group_id, organization_id) REFERENCES groups(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE ONLY assets
    ADD CONSTRAINT fk_assets_owner_user_organization FOREIGN KEY (owner_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE ONLY capability_grant_history
    ADD CONSTRAINT fk_capability_grant_history_actor_user_organization FOREIGN KEY (actor_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE SET NULL (actor_user_id);

ALTER TABLE ONLY capability_grant_history
    ADD CONSTRAINT fk_capability_grant_history_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE ONLY capability_grant_history
    ADD CONSTRAINT fk_capability_grant_history_subject_user_organization FOREIGN KEY (subject_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE ONLY capability_grant_history
    ADD CONSTRAINT fk_capability_grant_history_transfer FOREIGN KEY (transfer_id, organization_id) REFERENCES permission_transfers(id, organization_id) ON DELETE SET NULL (transfer_id);

ALTER TABLE ONLY capability_grants
    ADD CONSTRAINT fk_capability_grants_granted_by_user_organization FOREIGN KEY (granted_by_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE ONLY capability_grants
    ADD CONSTRAINT fk_capability_grants_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE ONLY capability_grants
    ADD CONSTRAINT fk_capability_grants_subject_group_organization FOREIGN KEY (subject_group_id, organization_id) REFERENCES groups(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE ONLY capability_grants
    ADD CONSTRAINT fk_capability_grants_subject_user_organization FOREIGN KEY (subject_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE ONLY directory_connectors
    ADD CONSTRAINT fk_directory_connectors_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT;

ALTER TABLE ONLY directory_connectors
    ADD CONSTRAINT fk_directory_connectors_provider FOREIGN KEY (provider_id) REFERENCES oidc_providers(id) ON DELETE CASCADE;

ALTER TABLE ONLY directory_sync_pending_plans
    ADD CONSTRAINT fk_directory_sync_pending_plans_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT;

ALTER TABLE ONLY directory_sync_pending_plans
    ADD CONSTRAINT fk_directory_sync_pending_plans_provider FOREIGN KEY (provider_id) REFERENCES oidc_providers(id) ON DELETE CASCADE;

ALTER TABLE ONLY directory_sync_status
    ADD CONSTRAINT fk_directory_sync_status_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT;

ALTER TABLE ONLY directory_sync_status
    ADD CONSTRAINT fk_directory_sync_status_provider FOREIGN KEY (provider_id) REFERENCES oidc_providers(id) ON DELETE CASCADE;

ALTER TABLE ONLY group_membership_history
    ADD CONSTRAINT fk_group_membership_history_actor_user_organization FOREIGN KEY (actor_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE SET NULL (actor_user_id);

ALTER TABLE ONLY group_membership_history
    ADD CONSTRAINT fk_group_membership_history_user_organization FOREIGN KEY (user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE ONLY group_memberships
    ADD CONSTRAINT fk_group_memberships_group_organization FOREIGN KEY (group_id, organization_id) REFERENCES groups(id, organization_id) ON DELETE CASCADE;

ALTER TABLE ONLY group_memberships
    ADD CONSTRAINT fk_group_memberships_user_organization FOREIGN KEY (user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE CASCADE;

ALTER TABLE ONLY group_stewards
    ADD CONSTRAINT fk_group_stewards_appointed_by_organization FOREIGN KEY (appointed_by_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE ONLY group_stewards
    ADD CONSTRAINT fk_group_stewards_group_organization FOREIGN KEY (group_id, organization_id) REFERENCES groups(id, organization_id) ON DELETE CASCADE;

ALTER TABLE ONLY group_stewards
    ADD CONSTRAINT fk_group_stewards_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE ONLY group_stewards
    ADD CONSTRAINT fk_group_stewards_user_organization FOREIGN KEY (user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE ONLY groups
    ADD CONSTRAINT fk_groups_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT;

ALTER TABLE ONLY groups
    ADD CONSTRAINT fk_groups_parent_group_organization FOREIGN KEY (parent_group_id, organization_id) REFERENCES groups(id, organization_id) ON DELETE SET NULL (parent_group_id);

ALTER TABLE ONLY groups
    ADD CONSTRAINT fk_groups_provider FOREIGN KEY (provider_id) REFERENCES oidc_providers(id) ON DELETE RESTRICT;

ALTER TABLE ONLY permission_transfer_objects
    ADD CONSTRAINT fk_permission_transfer_objects_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE ONLY permission_transfer_objects
    ADD CONSTRAINT fk_permission_transfer_objects_transfer FOREIGN KEY (transfer_id, organization_id) REFERENCES permission_transfers(id, organization_id) ON DELETE CASCADE;

ALTER TABLE ONLY permission_transfers
    ADD CONSTRAINT fk_permission_transfers_actor_user_organization FOREIGN KEY (actor_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE SET NULL (actor_user_id);

ALTER TABLE ONLY permission_transfers
    ADD CONSTRAINT fk_permission_transfers_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE ONLY succession_cases
    ADD CONSTRAINT fk_succession_cases_closed_by_user_organization FOREIGN KEY (closed_by_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE SET NULL (closed_by_user_id);

ALTER TABLE ONLY succession_cases
    ADD CONSTRAINT fk_succession_cases_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE;

-- account_state_complete_since: the complete account-state history starts with the installation.
INSERT INTO permission_history_retention_settings
  (id, retention_months, last_cutoff, updated_at, account_state_complete_since)
VALUES (
  1, 36,
  (date_trunc('month', now() AT TIME ZONE 'UTC') - interval '36 months') AT TIME ZONE 'UTC',
  now(),
  permission_history_next_boundary()
);

-- Delivered capabilities of every organization that already exists (the default one), dated to the
-- organization's own creation: the point-in-time answer must never say "nobody was allowed" for any
-- moment the organization existed. trg_organizations_seed_capability_grants delivers the same set to
-- every later organization.
INSERT INTO capability_grants (id, organization_id, capability, scope, subject_type, created_at)
SELECT gen_random_uuid(), organizations.id, delivered.capability, delivered.scope, 'ALL_ACCOUNTS',
       organizations.created_at
  FROM organizations
 CROSS JOIN (VALUES ('CREATE_SPACE', NULL), ('CREATE_LIBRARY', NULL), ('CREATE_PROMPT_LIBRARY', NULL),
                    ('CREATE_CONNECTOR_LIBRARY', 'TYPE:FILESYSTEM'),
                    ('CREATE_CONNECTOR_LIBRARY', 'TYPE:HTTP_DIRECTORY'),
                    ('CREATE_CONNECTOR_LIBRARY', 'TYPE:RSS_FEED'),
                    ('CREATE_CONNECTOR_LIBRARY', 'TYPE:CONFLUENCE'),
                    ('CREATE_CONNECTOR_LIBRARY', 'TYPE:S3')) AS delivered(capability, scope);

INSERT INTO capability_grant_history
  (id, organization_id, capability, scope, subject_type, cause, valid_from, created_at)
SELECT gen_random_uuid(), organization_id, capability, scope, 'ALL_ACCOUNTS', 'DELIVERED', created_at, now()
  FROM capability_grants;
