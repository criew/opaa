-- Fixture for RollingUpdateGuardTest: every script leaves the release that still runs while it is
-- applied intact, although several of them name a forbidden statement in a comment, a string
-- literal or a function body. Applied in version order to an empty schema.
CREATE TABLE guard_fixture (
    id uuid PRIMARY KEY,
    legacy text,
    defaulted text DEFAULT 'x',
    narrow character varying(100),
    state character varying(10),
    CONSTRAINT chk_guard_fixture_state CHECK (state IN ('A', 'B'))
);
