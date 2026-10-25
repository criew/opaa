SET LOCAL lock_timeout = '5s';

-- The retired retention variables an instance still finds set in its environment, with their
-- values (io.opaa.instance.RetiredRetentionVariables); NULL while it sets none. While a live
-- instance reports a value other than the setting in force, the deletion run it governs pauses.
ALTER TABLE backend_instances ADD COLUMN retired_settings jsonb;
