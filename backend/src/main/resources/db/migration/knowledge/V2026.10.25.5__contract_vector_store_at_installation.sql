SET LOCAL lock_timeout = '5s';

-- vector_store is created when the embedding model is chosen, with the dimension of its probe
-- embedding (ADR-0060, Entscheidung 10); the knowledge baseline still creates it with a fixed one.
-- On a database no instance has started on yet (no embedding_index row, which every start writes)
-- and without a chunk, the baseline's table goes again. No release runs on such a database, so the
-- drop breaks none; on an installation that has started, the table stays untouched.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM embedding_index) AND NOT EXISTS (SELECT 1 FROM vector_store) THEN
    DROP TABLE vector_store;
  END IF;
END $$;
