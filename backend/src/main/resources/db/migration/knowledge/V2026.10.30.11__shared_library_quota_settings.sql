SET LOCAL lock_timeout = '5s';

-- The house-wide storage quota per shared library as the system administration set it, at most the
-- operator's upper limit (opaa.library.quota-bytes); without the row the upper limit applies alone.
CREATE TABLE shared_library_quota_settings (
    id integer NOT NULL,
    quota_bytes bigint NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT shared_library_quota_settings_pkey PRIMARY KEY (id),
    CONSTRAINT chk_shared_library_quota_settings_bytes CHECK ((quota_bytes > 0)),
    CONSTRAINT chk_shared_library_quota_settings_singleton CHECK ((id = 1))
);
