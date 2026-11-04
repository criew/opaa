SET LOCAL lock_timeout = '5s';

-- Light or dark is each person's own choice; the operator no longer sets a default colour
-- scheme, so the column and its check go.
ALTER TABLE branding_settings DROP CONSTRAINT chk_branding_settings_color_scheme;
ALTER TABLE branding_settings DROP COLUMN default_color_scheme;
