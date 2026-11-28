SET LOCAL lock_timeout = '5s';
-- The branding page used to save the effective claim, so installations that never chose one hold
-- the former OPAA standard. NULL lets them follow the current standard again.
UPDATE branding_settings SET claim = NULL WHERE claim = 'Fragen. Belegen. Entscheiden.';
