SET LOCAL lock_timeout = '5s';

-- How many calendar years the grants of the anlassbezogene Klärung are kept, as the system
-- administration set it; without the row the factory default in the code applies (ADR-0060,
-- Entscheidung 1).
CREATE TABLE audit_incident_scope_retention_settings (
    id integer NOT NULL,
    retention_years integer NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT audit_incident_scope_retention_settings_pkey PRIMARY KEY (id),
    CONSTRAINT chk_audit_incident_scope_retention_settings_singleton CHECK ((id = 1)),
    CONSTRAINT chk_audit_incident_scope_retention_settings_years CHECK (((retention_years >= 1) AND (retention_years <= 10)))
);
