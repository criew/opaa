SET LOCAL lock_timeout = '5s';
-- The branding page used to save the effective product name, so installations that never chose one
-- hold the former OPAA standard spelling. NULL lets them follow the current standard again.
UPDATE branding_settings SET product_name = NULL WHERE product_name = 'OPAA';
