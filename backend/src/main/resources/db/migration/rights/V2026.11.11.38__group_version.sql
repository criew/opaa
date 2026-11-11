SET LOCAL lock_timeout = '5s';

-- Optimistic lock of groups: a write from a stale read fails instead of writing back a
-- deactivation, release or protection another transaction changed meanwhile. The constant default
-- adds the column without rewriting the table and keeps the INSERT of the predecessor version, which
-- does not know the column, valid during a rolling update.
ALTER TABLE groups ADD COLUMN version bigint NOT NULL DEFAULT 0;
