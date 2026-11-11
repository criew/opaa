SET LOCAL lock_timeout = '5s';

-- Optimistic lock of the connector type policies: a write from a stale read fails instead of
-- writing back a lock or profile requirement another transaction changed meanwhile. The constant
-- default adds the column without rewriting the table and keeps the INSERT of the predecessor
-- version, which does not know the column, valid during a rolling update.
ALTER TABLE connector_type_policies ADD COLUMN version bigint NOT NULL DEFAULT 0;
