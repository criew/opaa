SET LOCAL lock_timeout = '5s';

-- From which age in months an entry of the operational list "Ohne Zuständigkeit" is highlighted, as
-- the system administration set it; without the row the factory default in the code applies
-- (ADR-0060, Entscheidung 1).
CREATE TABLE succession_settings (
    id integer NOT NULL,
    aging_threshold_months integer NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT succession_settings_pkey PRIMARY KEY (id),
    CONSTRAINT chk_succession_settings_singleton CHECK ((id = 1)),
    CONSTRAINT chk_succession_settings_months CHECK (((aging_threshold_months >= 1) AND (aging_threshold_months <= 120)))
);
