SET LOCAL lock_timeout = '5s';

-- The generation of an embedding model (ADR-0061, Entscheidung 2): vectors are comparable only
-- under the same generation, model name and dimension. Every model change draws a new one from
-- embedding_generation_seq, which never hands out a number twice; an endpoint change keeps it.
-- reference_vectors holds the vectors of the fixed probe texts, one after the other, as the anchor
-- an endpoint change is compared with (Entscheidung 5).
ALTER TABLE embedding_model ADD COLUMN generation bigint DEFAULT 1 NOT NULL;
ALTER TABLE embedding_model ADD COLUMN reference_vectors real[];

-- generation belongs to the embedding column, target_generation to the target column of a running
-- change; the two are never equal. run_kind and run_generation name what the run works on, and
-- index_build_started_at marks a run building the index of the target column.
ALTER TABLE embedding_index ADD COLUMN generation bigint DEFAULT 1 NOT NULL;
ALTER TABLE embedding_index ADD COLUMN target_generation bigint;
ALTER TABLE embedding_index ADD COLUMN run_kind character varying(20);
ALTER TABLE embedding_index ADD COLUMN run_generation bigint;
ALTER TABLE embedding_index ADD COLUMN index_build_started_at timestamp with time zone;

-- The model before a confirmed change, kept until the switch or the discard (Entscheidung 3); the
-- key is encrypted with the settings key like in embedding_model.
CREATE TABLE embedding_model_previous (
    id smallint NOT NULL,
    base_url character varying(500) NOT NULL,
    model_identifier character varying(200) NOT NULL,
    api_key_ciphertext text,
    max_input_tokens integer NOT NULL,
    dimensions integer NOT NULL,
    installed_at timestamp with time zone NOT NULL,
    generation bigint NOT NULL,
    reference_vectors real[],
    CONSTRAINT embedding_model_previous_pkey PRIMARY KEY (id),
    CONSTRAINT chk_embedding_model_previous_singleton CHECK ((id = 1)),
    CONSTRAINT chk_embedding_model_previous_max_input_tokens CHECK ((max_input_tokens > 0)),
    CONSTRAINT chk_embedding_model_previous_dimensions CHECK ((dimensions > 0))
);

CREATE SEQUENCE embedding_generation_seq;

-- An installation in the middle of a re-embedding gets generation 2 for its target column; the
-- model row takes the generation of the column whose model and dimension it names, and one that
-- names none gets a fresh one. The sequence continues after the highest number given out.
DO $$
DECLARE
  highest bigint := 1;
BEGIN
  IF EXISTS (SELECT 1 FROM embedding_index WHERE id = 1 AND target_model IS NOT NULL) THEN
    UPDATE embedding_index SET target_generation = 2 WHERE id = 1;
    highest := 2;
  END IF;
  UPDATE embedding_model m SET generation = CASE
      WHEN i.target_model IS NOT NULL AND m.model_identifier = i.target_model
        AND m.dimensions = i.target_dimensions THEN 2
      WHEN m.model_identifier = i.model AND m.dimensions = i.dimensions THEN 1
      ELSE highest + 1
    END
  FROM embedding_index i
  WHERE i.id = 1;
  SELECT greatest(highest, coalesce(max(generation), 1)) INTO highest FROM embedding_model;
  PERFORM setval('embedding_generation_seq', highest);
END $$;
