-- ALTER TABLE guard_fixture DROP COLUMN legacy;
/* DROP TABLE guard_fixture; ALTER TABLE guard_fixture RENAME TO x; */
COMMENT ON COLUMN guard_fixture.legacy IS 'ALTER TABLE guard_fixture DROP COLUMN legacy';
CREATE FUNCTION guard_fixture_cleanup() RETURNS void LANGUAGE plpgsql AS $body$
BEGIN
  DROP TABLE guard_fixture_child;
END $body$;
