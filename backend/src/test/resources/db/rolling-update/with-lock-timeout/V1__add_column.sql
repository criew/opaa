-- Fixture for LockTimeoutMigrationTest: the ALTER TABLE with a lock_timeout.
SET LOCAL lock_timeout = '1s';
ALTER TABLE lock_fixture ADD COLUMN added text;
