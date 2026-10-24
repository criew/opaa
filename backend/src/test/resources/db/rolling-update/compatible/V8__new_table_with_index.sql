CREATE TABLE guard_fixture_child (id uuid PRIMARY KEY, parent_id uuid NOT NULL REFERENCES guard_fixture (id));
CREATE INDEX ix_guard_fixture_child_parent ON guard_fixture_child (parent_id);
