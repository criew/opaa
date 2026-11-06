SET LOCAL lock_timeout = '5s';

-- The text recognition (OCR) model role of the installation, one row once the administration saves
-- it; without a row the factory default applies (off, docling-serve, unbound). connection_type is
-- DOCLING_SERVE or OPENAI_VISION; model_identifier is only stored for a vision model. The key is
-- encrypted with the settings key. The operation switch and the limits stay in the configuration.
CREATE TABLE text_recognition_model (
    id smallint NOT NULL,
    enabled boolean NOT NULL,
    connection_type character varying(32) NOT NULL,
    base_url character varying(500),
    model_identifier character varying(200),
    api_key_ciphertext text,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT text_recognition_model_pkey PRIMARY KEY (id),
    CONSTRAINT chk_text_recognition_model_singleton CHECK ((id = 1))
);

CREATE TRIGGER trg_text_recognition_model_invalidation AFTER INSERT OR DELETE OR UPDATE OR TRUNCATE ON text_recognition_model FOR EACH STATEMENT EXECUTE FUNCTION notify_cache_invalidation('text-recognition-model');
