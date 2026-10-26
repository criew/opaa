SET LOCAL lock_timeout = '5s';

-- The rerank model role of the installation (ADR-0060, Entscheidung 10), one row once the
-- administration saves it; without a row the factory default applies (off, unbound). The key is
-- encrypted with the settings key. Timeout and probe interval stay in the configuration.
CREATE TABLE rerank_model (
    id smallint NOT NULL,
    enabled boolean NOT NULL,
    base_url character varying(500),
    model_identifier character varying(200),
    api_key_ciphertext text,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT rerank_model_pkey PRIMARY KEY (id),
    CONSTRAINT chk_rerank_model_singleton CHECK ((id = 1))
);

CREATE TRIGGER trg_rerank_model_invalidation AFTER INSERT OR DELETE OR UPDATE OR TRUNCATE ON rerank_model FOR EACH STATEMENT EXECUTE FUNCTION notify_cache_invalidation('rerank-model');
