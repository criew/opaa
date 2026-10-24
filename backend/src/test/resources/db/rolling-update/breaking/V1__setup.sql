-- Fixture for RollingUpdateGuardTest: after the setup, every script breaks the release that still runs
-- while it is applied. Applied in version order to an empty schema.
CREATE TABLE guard_fixture (
    id uuid PRIMARY KEY,
    legacy text,
    dynamic text,
    renamed text,
    optional text,
    required text NOT NULL,
    defaulted text DEFAULT 'x',
    narrow character varying(100),
    state character varying(10),
    plain text,
    CONSTRAINT chk_guard_fixture_state CHECK (state IN ('A', 'B'))
);
CREATE TABLE guard_fixture_doomed (id integer PRIMARY KEY);
CREATE TABLE guard_fixture_old_name (id integer PRIMARY KEY);
