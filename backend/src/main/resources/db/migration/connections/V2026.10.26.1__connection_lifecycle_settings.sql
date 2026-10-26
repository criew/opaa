SET LOCAL lock_timeout = '5s';

-- The thresholds and periods of the connections as the system administration set them, one row per
-- setting; without a row the factory default in ConnectionLifecycleSetting applies. The bounds of
-- each setting are checked here as well, so no SQL stretches them.
CREATE TABLE connection_lifecycle_settings (
    setting character varying(40) NOT NULL,
    setting_value integer NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT connection_lifecycle_settings_pkey PRIMARY KEY (setting),
    CONSTRAINT chk_connection_lifecycle_settings_bounds CHECK ((
        (setting <> 'INACTIVITY_THRESHOLD_DAYS' OR (setting_value >= 30 AND setting_value <= 365))
        AND (setting <> 'PRIVATE_LIBRARY_DELETION_DAYS' OR (setting_value >= 1 AND setting_value <= 90))
        AND (setting <> 'EXPIRED_CONNECTION_WARNING_THRESHOLD' OR (setting_value >= 1 AND setting_value <= 1000))
    ))
);
