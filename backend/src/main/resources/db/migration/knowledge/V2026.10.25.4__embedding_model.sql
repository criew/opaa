SET LOCAL lock_timeout = '5s';

-- The embedding model of the installation (ADR-0060, Entscheidung 10), one row once it is chosen:
-- written when the installation is completed, or once at the start of an installation that
-- predates it. dimensions is what a probe embedding returned or what the existing vector column
-- declares; vector_store is created with it. The key is encrypted with the settings key.
CREATE TABLE embedding_model (
    id smallint NOT NULL,
    base_url character varying(500) NOT NULL,
    model_identifier character varying(200) NOT NULL,
    api_key_ciphertext text,
    max_input_tokens integer NOT NULL,
    dimensions integer NOT NULL,
    installed_at timestamp with time zone NOT NULL,
    CONSTRAINT embedding_model_pkey PRIMARY KEY (id),
    CONSTRAINT chk_embedding_model_singleton CHECK ((id = 1)),
    CONSTRAINT chk_embedding_model_max_input_tokens CHECK ((max_input_tokens > 0)),
    CONSTRAINT chk_embedding_model_dimensions CHECK ((dimensions > 0))
);

CREATE TRIGGER trg_embedding_model_invalidation AFTER INSERT OR DELETE OR UPDATE OR TRUNCATE ON embedding_model FOR EACH STATEMENT EXECUTE FUNCTION notify_cache_invalidation('embedding-model');
