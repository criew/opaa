SET LOCAL lock_timeout = '5s';

-- The installation-wide rhythm of the full sync per source type as the system administration set
-- it; without a row the factory default of the connector applies. A library may set its own.
CREATE TABLE full_sync_rhythm_settings (
    source_type character varying(20) NOT NULL,
    interval_days integer NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT full_sync_rhythm_settings_pkey PRIMARY KEY (source_type),
    CONSTRAINT chk_full_sync_rhythm_settings_days CHECK (((interval_days >= 1) AND (interval_days <= 365)))
);
