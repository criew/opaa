SET LOCAL lock_timeout = '5s';

-- The periods of the automatic chat cleanup of the spaces as the system administration set them;
-- without the row the factory defaults in the code apply (ADR-0060, Entscheidung 1).
CREATE TABLE chat_auto_cleanup_settings (
    id integer NOT NULL,
    archive_after_days integer NOT NULL,
    delete_after_days integer NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT chat_auto_cleanup_settings_pkey PRIMARY KEY (id),
    CONSTRAINT chk_chat_auto_cleanup_settings_singleton CHECK ((id = 1)),
    CONSTRAINT chk_chat_auto_cleanup_settings_archive CHECK (((archive_after_days >= 90) AND (archive_after_days <= 3650))),
    CONSTRAINT chk_chat_auto_cleanup_settings_delete CHECK (((delete_after_days >= 30) AND (delete_after_days <= 3650)))
);
