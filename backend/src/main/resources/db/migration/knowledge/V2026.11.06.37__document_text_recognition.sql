SET LOCAL lock_timeout = '5s';

-- Text recognition (OCR) of a scanned PDF, at most one row per document, in the same database as
-- the document and its chunks so a restored backup keeps all three consistent. A row holds either
-- the stored recognition (recognized_content: DoclingDocument JSON or Markdown per page, text and
-- coordinates only, never an image), from which a re-index cuts the chunks again without the
-- service, or a lock (blocked_reason) after a document-caused refusal or the page limit. Both apply
-- only to the file with this checksum, recognised by connection type and configuration
-- (configuration_id: connection type, address and model of the role, never its key) at this
-- version of the pdf-ocr pipeline. Deleted with the document.
CREATE TABLE document_text_recognition (
    document_id uuid NOT NULL,
    checksum character varying(64) NOT NULL,
    connection_type character varying(32) NOT NULL,
    configuration_id character varying(64) NOT NULL,
    pipeline_version smallint NOT NULL,
    page_count integer,
    recognized_content text,
    recognized_at timestamp with time zone,
    blocked_reason character varying(500),
    blocked_at timestamp with time zone,
    CONSTRAINT document_text_recognition_pkey PRIMARY KEY (document_id),
    CONSTRAINT fk_document_text_recognition_document FOREIGN KEY (document_id)
        REFERENCES documents (id) ON DELETE CASCADE,
    CONSTRAINT chk_document_text_recognition_state CHECK (
        (recognized_content IS NULL) <> (blocked_reason IS NULL))
);

CREATE INDEX idx_document_text_recognition_blocked
    ON document_text_recognition (document_id) WHERE blocked_reason IS NOT NULL;
