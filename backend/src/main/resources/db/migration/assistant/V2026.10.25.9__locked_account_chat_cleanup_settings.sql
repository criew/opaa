SET LOCAL lock_timeout = '5s';

-- Switch and period of the deletion of the chats of locked local accounts as the system
-- administration set them; without the row the factory defaults in the code apply (ADR-0060,
-- Entscheidung 1).
CREATE TABLE locked_account_chat_cleanup_settings (
    id integer NOT NULL,
    enabled boolean NOT NULL,
    delete_after_days integer NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT locked_account_chat_cleanup_settings_pkey PRIMARY KEY (id),
    CONSTRAINT chk_locked_account_chat_cleanup_settings_singleton CHECK ((id = 1)),
    CONSTRAINT chk_locked_account_chat_cleanup_settings_days CHECK (((delete_after_days >= 90) AND (delete_after_days <= 3650)))
);
