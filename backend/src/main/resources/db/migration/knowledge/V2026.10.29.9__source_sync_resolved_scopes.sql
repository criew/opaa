SET LOCAL lock_timeout = '5s';

-- What the scope "Alle" of a Confluence, S3, Google Drive or SharePoint library resolved to in its
-- last run, as a JSON object of scope key to shown name. The next run compares against it and names
-- the scopes that appeared or vanished in its protocol. NULL before the first such run.
ALTER TABLE source_sync_state ADD COLUMN resolved_scopes jsonb;
