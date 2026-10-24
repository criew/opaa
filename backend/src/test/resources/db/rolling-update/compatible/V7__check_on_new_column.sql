ALTER TABLE guard_fixture ADD COLUMN level integer;
ALTER TABLE guard_fixture ADD CONSTRAINT chk_guard_fixture_level CHECK (level > 0);
