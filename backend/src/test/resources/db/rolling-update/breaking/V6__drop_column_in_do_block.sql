DO $$
BEGIN
  EXECUTE 'ALTER TABLE guard_fixture DROP ' || 'COLUMN dynamic';
END $$;
