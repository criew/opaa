SET LOCAL lock_timeout = '5s';

-- The plausibility threshold of the directory sync in percent, installation-wide, as the system
-- administration set it; without the row the factory default in the code applies (ADR-0060,
-- Entscheidung 1).
CREATE TABLE directory_sync_settings (
    id integer NOT NULL,
    change_threshold_percent integer NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT directory_sync_settings_pkey PRIMARY KEY (id),
    CONSTRAINT chk_directory_sync_settings_singleton CHECK ((id = 1)),
    CONSTRAINT chk_directory_sync_settings_percent CHECK (((change_threshold_percent >= 1) AND (change_threshold_percent <= 100)))
);
