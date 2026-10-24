ALTER TABLE guard_fixture DROP CONSTRAINT chk_guard_fixture_state;
ALTER TABLE guard_fixture ADD CONSTRAINT chk_guard_fixture_state CHECK (state IN ('A', 'B', 'C'));
