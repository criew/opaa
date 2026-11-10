SET LOCAL lock_timeout = '5s';

-- The responsible unit of an agent or a document template (a group or a function, never a
-- person alone) and its lock. missing_since starts the period without a responsible unit; after
-- it the object is locked with cause NO_RESPONSIBLE, never deleted. A row exists for every asset
-- of a type that carries a responsibility; the existing ones start without a unit.

CREATE TABLE asset_responsibilities (
    asset_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    responsible_group_id uuid,
    responsible_function character varying(200),
    missing_since timestamp with time zone,
    locked_at timestamp with time zone,
    lock_cause character varying(20),
    lock_reason character varying(500),
    CONSTRAINT asset_responsibilities_pkey PRIMARY KEY (asset_id),
    CONSTRAINT chk_asset_responsibilities_one_unit CHECK (((responsible_group_id IS NULL) OR (responsible_function IS NULL))),
    CONSTRAINT chk_asset_responsibilities_lock CHECK ((((locked_at IS NULL) AND (lock_cause IS NULL) AND (lock_reason IS NULL)) OR ((locked_at IS NOT NULL) AND (lock_cause IS NOT NULL) AND (lock_reason IS NOT NULL)))),
    CONSTRAINT chk_asset_responsibilities_lock_cause CHECK (((lock_cause IS NULL) OR (lock_cause IN ('MANUAL', 'NO_RESPONSIBLE')))),
    CONSTRAINT fk_asset_responsibilities_asset FOREIGN KEY (asset_id, organization_id) REFERENCES assets(id, organization_id) ON DELETE CASCADE,
    CONSTRAINT fk_asset_responsibilities_group FOREIGN KEY (responsible_group_id, organization_id) REFERENCES groups(id, organization_id) ON DELETE SET NULL (responsible_group_id)
);

CREATE INDEX idx_asset_responsibilities_group ON asset_responsibilities (responsible_group_id);

CREATE INDEX idx_asset_responsibilities_organization ON asset_responsibilities (organization_id);

INSERT INTO asset_responsibilities (asset_id, organization_id)
SELECT id, organization_id FROM assets WHERE asset_type IN ('AGENT', 'DOCUMENT_TEMPLATE')
ON CONFLICT DO NOTHING;

-- After how many days without a responsible unit the lock follows; the row exists only once the
-- system administration set a value, without it the factory default in the code applies.
CREATE TABLE responsibility_lock_settings (
    id integer NOT NULL,
    lock_after_days integer NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT responsibility_lock_settings_pkey PRIMARY KEY (id),
    CONSTRAINT chk_responsibility_lock_settings_singleton CHECK ((id = 1)),
    CONSTRAINT chk_responsibility_lock_settings_days CHECK (((lock_after_days >= 1) AND (lock_after_days <= 365)))
);
