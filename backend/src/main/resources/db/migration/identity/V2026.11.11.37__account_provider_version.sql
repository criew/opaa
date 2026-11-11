SET LOCAL lock_timeout = '5s';

-- Optimistic lock of accounts and sign-in providers: a write from a stale read fails instead of
-- writing back a role, lock or switch another transaction changed meanwhile. The constant default
-- adds the column without rewriting the table and keeps the INSERT of the predecessor version, which
-- does not know the column, valid during a rolling update.
ALTER TABLE users ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE oidc_providers ADD COLUMN version bigint NOT NULL DEFAULT 0;
