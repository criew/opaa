ALTER TABLE guard_fixture ADD COLUMN owner text NOT NULL DEFAULT 'person';
ALTER TABLE guard_fixture ALTER COLUMN owner DROP DEFAULT;
