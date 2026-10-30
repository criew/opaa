SET LOCAL lock_timeout = '5s';

-- The lockout after failed sign-ins as the system administration set it: failed attempts until the
-- lock and its duration in minutes, each empty while the factory default in LocalAuthSettings
-- applies (ADR-0060, Entscheidungen 1 and 17). The limits come from the configuration and are
-- checked by the service and applied on every read, so the columns carry no CHECK of their own.
ALTER TABLE local_auth_settings ADD COLUMN lockout_max_attempts integer;
ALTER TABLE local_auth_settings ADD COLUMN lockout_duration_minutes integer;
