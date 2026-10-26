SET LOCAL lock_timeout = '5s';

-- How many days before a library's release expires its responsible person is reminded, as the
-- system administration set it; empty while the factory default in ExternalAccessDefaults applies
-- (ADR-0060, Entscheidung 1). The bound follows the longest Befristung of the configuration and is
-- checked by the service.
ALTER TABLE external_access_settings ADD COLUMN release_reminder_lead_days integer;
