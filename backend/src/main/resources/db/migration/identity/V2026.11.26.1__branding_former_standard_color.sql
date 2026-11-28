SET LOCAL lock_timeout = '5s';

-- The branding page used to save the effective accent, so installations that never chose a colour
-- hold the former OPAA standard. NULL lets them follow the current standard again.
UPDATE branding_settings SET primary_color = NULL WHERE upper(primary_color) = '#1292EE';
