SET LOCAL lock_timeout = '5s';

-- The transcription model role of the installation (ADR-0060, Entscheidung 10), one row once the
-- administration saves it; without a row the factory default applies (off, unbound, language de).
-- language null lets the model detect the language. The key is encrypted with the settings key.
-- Audio size, timeout and concurrency stay in the configuration.
CREATE TABLE transcription_model (
    id smallint NOT NULL,
    enabled boolean NOT NULL,
    base_url character varying(500),
    model_identifier character varying(200),
    api_key_ciphertext text,
    language character varying(8),
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT transcription_model_pkey PRIMARY KEY (id),
    CONSTRAINT chk_transcription_model_singleton CHECK ((id = 1))
);

CREATE TRIGGER trg_transcription_model_invalidation AFTER INSERT OR DELETE OR UPDATE OR TRUNCATE ON transcription_model FOR EACH STATEMENT EXECUTE FUNCTION notify_cache_invalidation('transcription-model');
