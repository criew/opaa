SET LOCAL lock_timeout = '5s';

-- Document templates: an asset type whose content is one DOCX file with placeholders. The shell row
-- in assets carries name, owner and release level; document_templates the file's description and
-- the placeholders read from it, document_template_files the bytes, loaded only for a download or
-- a filling.
CREATE TABLE document_templates (
    id uuid NOT NULL,
    asset_type character varying(30) DEFAULT 'DOCUMENT_TEMPLATE'::character varying NOT NULL,
    organization_id uuid NOT NULL,
    file_name character varying(255) NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 character varying(64) NOT NULL,
    placeholders jsonb DEFAULT '[]'::jsonb NOT NULL,
    CONSTRAINT document_templates_pkey PRIMARY KEY (id),
    CONSTRAINT uk_document_templates_id_organization UNIQUE (id, organization_id),
    CONSTRAINT chk_document_templates_asset_type CHECK (((asset_type)::text = 'DOCUMENT_TEMPLATE'::text)),
    CONSTRAINT chk_document_templates_size CHECK ((size_bytes > 0)),
    CONSTRAINT chk_document_templates_placeholders_array CHECK ((jsonb_typeof(placeholders) = 'array'::text)),
    CONSTRAINT fk_document_templates_asset FOREIGN KEY (id, asset_type, organization_id)
        REFERENCES assets(id, asset_type, organization_id) ON DELETE CASCADE
);

CREATE TABLE document_template_files (
    template_id uuid NOT NULL,
    content bytea NOT NULL,
    CONSTRAINT document_template_files_pkey PRIMARY KEY (template_id),
    CONSTRAINT fk_document_template_files_template FOREIGN KEY (template_id)
        REFERENCES document_templates(id) ON DELETE CASCADE
);

-- The installation switch "Vorlagen": one row at most, absent while the switch is as delivered
-- (off). Read on every request without a cache, so a change holds on every instance at once.
CREATE TABLE document_template_settings (
    id integer NOT NULL,
    enabled boolean NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    updated_by uuid,
    CONSTRAINT document_template_settings_pkey PRIMARY KEY (id),
    CONSTRAINT chk_document_template_settings_singleton CHECK ((id = 1))
);
