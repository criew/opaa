SET LOCAL lock_timeout = '5s';

-- A service account is the identity of one Fachverfahren on the external access channel (ADR-0035,
-- Entscheidung 7): no person, no sign-in, no role. The responsible person is a contact for renewals
-- and outlives nothing - deleting her account leaves the column empty and the tokens working.
CREATE TABLE service_accounts (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    name character varying(120) NOT NULL,
    description character varying(1000),
    responsible_user_id uuid,
    rate_limit_per_hour integer,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    deactivated_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT service_accounts_pkey PRIMARY KEY (id),
    CONSTRAINT chk_service_accounts_name CHECK (btrim((name)::text) <> ''::text),
    CONSTRAINT chk_service_accounts_rate_limit CHECK (
        rate_limit_per_hour IS NULL OR (rate_limit_per_hour >= 1 AND rate_limit_per_hour <= 10000)),
    CONSTRAINT fk_service_accounts_organization FOREIGN KEY (organization_id)
        REFERENCES organizations(id) ON DELETE CASCADE,
    CONSTRAINT fk_service_accounts_responsible_user_organization FOREIGN KEY (responsible_user_id, organization_id)
        REFERENCES users(id, organization_id) ON DELETE SET NULL (responsible_user_id)
);

CREATE INDEX idx_service_accounts_organization ON service_accounts USING btree (organization_id);

CREATE INDEX idx_service_accounts_responsible_user ON service_accounts USING btree (responsible_user_id);

-- The tokens of a service account. Same value shape and lookup hash as a personal token, no library
-- selection and no day of last use; expiry_reminded_days is the claim of the expiry reminder.
CREATE TABLE service_account_tokens (
    id uuid NOT NULL,
    service_account_id uuid NOT NULL,
    name character varying(120) NOT NULL,
    token_prefix character varying(32) NOT NULL,
    token_lookup_hash character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    revocation_reason character varying(32),
    lapse_recorded_at timestamp with time zone,
    expiry_reminded_days integer,
    CONSTRAINT service_account_tokens_pkey PRIMARY KEY (id),
    CONSTRAINT ux_service_account_tokens_hash UNIQUE (token_lookup_hash),
    CONSTRAINT chk_service_account_tokens_name CHECK (btrim((name)::text) <> ''::text),
    CONSTRAINT chk_service_account_tokens_revocation CHECK ((revoked_at IS NULL) = (revocation_reason IS NULL)),
    CONSTRAINT chk_service_account_tokens_revocation_reason CHECK (
        revocation_reason IS NULL OR revocation_reason IN ('OWNER', 'ADMIN', 'ACCOUNT_LIFECYCLE', 'EXPIRED')),
    CONSTRAINT fk_service_account_tokens_account FOREIGN KEY (service_account_id)
        REFERENCES service_accounts(id) ON DELETE CASCADE
);

CREATE INDEX idx_service_account_tokens_account ON service_account_tokens USING btree (service_account_id);

CREATE INDEX idx_service_account_tokens_expires_at ON service_account_tokens USING btree (expires_at);

-- The consent of a library administration to one service account, historised: a row is never
-- deleted, a change ends it (ended_at, end_reason) and starts the next one. At most one row per pair
-- is open; an open row whose expires_at has passed no longer takes effect. Like
-- asset_visibility_history it has no foreign key on the library, so it outlives its deletion;
-- reminder_sent_at and expiry_recorded_at are the claims of the reminder and of the expiry entry.
CREATE TABLE service_account_library_consents (
    id uuid NOT NULL,
    service_account_id uuid NOT NULL,
    library_id uuid NOT NULL,
    granted_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone,
    end_reason character varying(20),
    reminder_sent_at timestamp with time zone,
    expiry_recorded_at timestamp with time zone,
    CONSTRAINT service_account_library_consents_pkey PRIMARY KEY (id),
    CONSTRAINT chk_service_account_library_consents_end CHECK ((ended_at IS NULL) = (end_reason IS NULL)),
    CONSTRAINT chk_service_account_library_consents_end_reason CHECK (
        end_reason IS NULL OR end_reason IN ('WITHDRAWN', 'REPLACED', 'EXPIRED', 'LIBRARY_DELETED')),
    CONSTRAINT fk_service_account_library_consents_account FOREIGN KEY (service_account_id)
        REFERENCES service_accounts(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_service_account_library_consents_open
    ON service_account_library_consents USING btree (service_account_id, library_id)
    WHERE ended_at IS NULL;

CREATE INDEX idx_service_account_library_consents_library
    ON service_account_library_consents USING btree (library_id);
