-- Module knowledge: pgvector, knowledge libraries with their folders and documents, indexing runs
-- and source state, the full-text and embedding index, the metadata schema with the delivered
-- Dokumentart vocabulary, the LLM model catalogue and the model roles of the installation.
-- vector_store is no part of it: the installation creates it once the embedding model is chosen.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE FUNCTION knowledge_libraries_guard_owner_only_release() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO 'pg_catalog', '${flyway:defaultSchema}', 'pg_temp'
    AS $$
BEGIN
  IF (NEW.external_access_state)::text <> 'NEVER_SET'::text
      AND EXISTS (SELECT 1 FROM assets a WHERE a.id = NEW.id AND a.owner_only) THEN
    RAISE EXCEPTION 'owner-only library % is never released for external access', NEW.id
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END; $$;

CREATE TABLE chunk_full_text (
    chunk_id uuid NOT NULL,
    document_id uuid NOT NULL,
    library_id uuid NOT NULL,
    content_tsv tsvector NOT NULL,
    content_tsv_version smallint DEFAULT 1 NOT NULL
);

CREATE TABLE document_keywords (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    library_id uuid NOT NULL,
    keyword character varying(40) NOT NULL,
    model_id character varying(255),
    extraction_version integer,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE document_metadata_values (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    field_key character varying(100) NOT NULL,
    value_state character varying(20) DEFAULT 'SET'::character varying NOT NULL,
    text_value character varying(1000),
    vocabulary_code character varying(50),
    date_value date,
    date_precision character varying(10),
    origin character varying(20) NOT NULL,
    confidence double precision,
    actor_user_id uuid,
    model_id character varying(255),
    extraction_version integer,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    library_field_id uuid,
    library_value_id uuid,
    CONSTRAINT chk_document_metadata_values_confidence_only_derived CHECK (((confidence IS NULL) OR ((origin)::text = 'DERIVED'::text))),
    CONSTRAINT chk_document_metadata_values_core_field_type CHECK (((((field_key)::text = 'title'::text) AND (vocabulary_code IS NULL) AND (date_value IS NULL)) OR (((field_key)::text = 'document_type'::text) AND (text_value IS NULL) AND (date_value IS NULL)) OR (((field_key)::text = 'document_date'::text) AND (text_value IS NULL) AND (vocabulary_code IS NULL)) OR (field_key NOT IN ('title', 'document_type', 'document_date')))),
    CONSTRAINT chk_document_metadata_values_date_has_precision CHECK (((date_value IS NULL) = (date_precision IS NULL))),
    CONSTRAINT chk_document_metadata_values_extraction_version CHECK ((((origin)::text = 'MANUAL'::text) OR (extraction_version IS NOT NULL))),
    CONSTRAINT chk_document_metadata_values_library_field CHECK (((library_field_id IS NOT NULL) = ((field_key)::text ~~ 'lib:%'::text))),
    CONSTRAINT chk_document_metadata_values_library_value CHECK (((library_value_id IS NULL) OR ((library_field_id IS NOT NULL) AND (text_value IS NOT NULL)))),
    CONSTRAINT chk_document_metadata_values_not_determinable_is_manual CHECK ((((value_state)::text <> 'NOT_DETERMINABLE'::text) OR ((origin)::text = 'MANUAL'::text))),
    CONSTRAINT chk_document_metadata_values_one_value CHECK (((((value_state)::text = 'NOT_DETERMINABLE'::text) AND (text_value IS NULL) AND (vocabulary_code IS NULL) AND (date_value IS NULL)) OR (((value_state)::text = 'SET'::text) AND (((((text_value IS NOT NULL))::integer + ((vocabulary_code IS NOT NULL))::integer) + ((date_value IS NOT NULL))::integer) = 1)))),
    CONSTRAINT chk_document_metadata_values_origin CHECK ((origin IN ('DETERMINISTIC', 'DERIVED', 'MANUAL'))),
    CONSTRAINT chk_document_metadata_values_precision CHECK (((date_precision IS NULL) OR (date_precision IN ('DAY', 'MONTH', 'YEAR')))),
    CONSTRAINT chk_document_metadata_values_state CHECK ((value_state IN ('SET', 'NOT_DETERMINABLE')))
);

CREATE TABLE document_type_suffix_exclusions (
    code character varying(50) NOT NULL,
    token character varying(100) NOT NULL
);

CREATE TABLE document_type_suffixes (
    code character varying(50) NOT NULL,
    suffix character varying(100) NOT NULL,
    min_prefix_length integer DEFAULT 3 NOT NULL,
    CONSTRAINT chk_document_type_suffixes_min_prefix_length CHECK ((min_prefix_length >= 1))
);

CREATE TABLE document_type_synonyms (
    code character varying(50) NOT NULL,
    synonym character varying(100) NOT NULL
);

CREATE TABLE document_type_vocabulary (
    code character varying(50) NOT NULL,
    label character varying(100) NOT NULL,
    sort_order integer NOT NULL,
    CONSTRAINT chk_document_type_vocabulary_code CHECK (((code)::text ~ '^[A-Z][A-Z0-9_]*$'::text))
);

CREATE TABLE documents (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    file_name text NOT NULL,
    file_path character varying(2000) NOT NULL,
    content_type character varying(255),
    file_size bigint,
    chunk_count integer DEFAULT 0,
    indexed_at timestamp with time zone,
    checksum character varying(64),
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    source_type character varying(20) DEFAULT 'FILESYSTEM'::character varying NOT NULL,
    last_modified_remote text,
    library_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    uploaded_by_user_id uuid,
    source_entry_url character varying(2000),
    error_message text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    folder_id uuid,
    parent_document_id uuid,
    source_container_key text,
    source_hierarchy_path text,
    metadata_extraction_version integer,
    context_prefix_stamp character varying(64),
    context_prefix_eligible boolean,
    context_prefix_title text,
    model_extraction_version integer,
    keyword_extraction_version integer,
    processing_instance_id uuid,
    malware_signature text,
    CONSTRAINT chk_documents_source_type CHECK (((source_type)::text ~ '^[A-Z][A-Z0-9_]*$'::text)),
    CONSTRAINT chk_documents_status CHECK ((status IN ('PENDING', 'INDEXED', 'FAILED')))
);

CREATE TABLE embedding_index (
    id integer NOT NULL,
    model text,
    base_url text,
    dimensions integer NOT NULL,
    target_model text,
    target_base_url text,
    target_dimensions integer,
    run_status character varying(20),
    run_started_at timestamp with time zone,
    run_started_by uuid,
    run_organization_id uuid,
    run_finished_at timestamp with time zone,
    run_message text,
    updated_at timestamp with time zone NOT NULL,
    run_owner_instance_id uuid,
    generation bigint DEFAULT 1 NOT NULL,
    target_generation bigint,
    run_kind character varying(20),
    run_generation bigint,
    index_build_started_at timestamp with time zone,
    CONSTRAINT chk_embedding_index_dimensions CHECK ((dimensions > 0)),
    CONSTRAINT chk_embedding_index_run_status CHECK ((run_status IN ('RUNNING', 'CANCELLED', 'FAILED', 'COMPLETED'))),
    CONSTRAINT chk_embedding_index_singleton CHECK ((id = 1)),
    CONSTRAINT chk_embedding_index_target CHECK ((((target_model IS NULL) AND (target_dimensions IS NULL)) OR ((target_model IS NOT NULL) AND (target_dimensions > 0))))
);

CREATE TABLE embedding_rate_measurements (
    embedding_model text NOT NULL,
    measured_chunks bigint NOT NULL,
    measured_nanos bigint NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

CREATE TABLE indexing_jobs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    status character varying(20) DEFAULT 'RUNNING'::character varying NOT NULL,
    documents_total integer DEFAULT 0,
    documents_processed integer DEFAULT 0,
    documents_failed integer DEFAULT 0,
    documents_skipped integer DEFAULT 0,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    error_message text,
    library_id uuid,
    documents_indexed_total integer DEFAULT 0 NOT NULL,
    events_truncated_count integer DEFAULT 0 NOT NULL,
    last_progress_at timestamp with time zone NOT NULL,
    organization_id uuid NOT NULL,
    triggered_by character varying(20) DEFAULT 'MANUAL'::character varying NOT NULL,
    run_mode character varying(20) DEFAULT 'FULL'::character varying NOT NULL,
    requests_sent integer,
    throttle_count integer,
    throttle_wait_millis bigint,
    attachments_processed integer,
    attachments_skipped integer,
    attachments_failed integer,
    incomplete boolean DEFAULT false NOT NULL,
    listing_complete boolean,
    unlisted_scope_keys text,
    bytes_downloaded bigint,
    unreadable_scope_count integer,
    failure_category character varying(40),
    owner_instance_id uuid,
    CONSTRAINT chk_indexing_jobs_failure_category CHECK (((failure_category IS NULL) OR ((failure_category)::text ~ '^[A-Z][A-Z_]*$'::text))),
    CONSTRAINT chk_indexing_jobs_run_mode CHECK ((run_mode IN ('FULL', 'INCREMENTAL', 'EVENT'))),
    CONSTRAINT chk_indexing_jobs_status CHECK ((status IN ('RUNNING', 'COMPLETED', 'FAILED'))),
    CONSTRAINT chk_indexing_jobs_triggered_by CHECK ((triggered_by IN ('MANUAL', 'SCHEDULED', 'WEBHOOK')))
);

CREATE TABLE indexing_run_events (
    id uuid NOT NULL,
    job_id uuid NOT NULL,
    category character varying(30) NOT NULL,
    message text NOT NULL,
    reference text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE knowledge_libraries (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    source_type character varying(20) NOT NULL,
    source_path character varying(2000),
    source_url character varying(2000),
    source_credentials text,
    schedule_enabled boolean DEFAULT false NOT NULL,
    schedule_cron character varying(100),
    diagnostics_locked boolean DEFAULT true NOT NULL,
    source_webhook_secret character varying(3000),
    core_context_prefix_document_type boolean DEFAULT false NOT NULL,
    core_context_prefix_document_date boolean DEFAULT false NOT NULL,
    model_extraction_enabled boolean DEFAULT false NOT NULL,
    keywords_enabled boolean DEFAULT false NOT NULL,
    source_settings jsonb,
    external_access_state character varying(20) DEFAULT 'NEVER_SET'::character varying NOT NULL,
    external_access_expires_at timestamp with time zone,
    external_access_set_at timestamp with time zone,
    external_access_set_by_user_id uuid,
    external_access_reminder_sent_at timestamp with time zone,
    erasure_requested_at timestamp with time zone,
    erasure_cause character varying(40),
    schedule_booked_at timestamp with time zone,
    schedule_booked_cron character varying(100),
    CONSTRAINT chk_knowledge_libraries_erasure CHECK ((((erasure_requested_at IS NULL) = (erasure_cause IS NULL)) AND ((erasure_cause IS NULL) OR ((erasure_cause)::text ~ '^[A-Z][A-Z_]*$'::text)))),
    CONSTRAINT chk_knowledge_libraries_external_access CHECK (
CASE external_access_state
    WHEN 'NEVER_SET'::text THEN ((external_access_expires_at IS NULL) AND (external_access_set_at IS NULL) AND (external_access_set_by_user_id IS NULL) AND (external_access_reminder_sent_at IS NULL))
    WHEN 'ACTIVE'::text THEN ((external_access_expires_at IS NOT NULL) AND (external_access_set_at IS NOT NULL))
    ELSE (external_access_set_at IS NOT NULL)
END),
    CONSTRAINT chk_knowledge_libraries_external_access_state CHECK ((external_access_state IN ('NEVER_SET', 'ACTIVE', 'WITHDRAWN', 'EXPIRED', 'SUSPENDED'))),
    CONSTRAINT chk_knowledge_libraries_schedule CHECK ((((schedule_enabled = false) AND (schedule_cron IS NULL)) OR ((schedule_enabled = true) AND (schedule_cron IS NOT NULL) AND ((source_type)::text <> 'UPLOAD'::text)))),
    CONSTRAINT chk_knowledge_libraries_source_settings CHECK (((source_settings IS NULL) OR (jsonb_typeof(source_settings) = 'object'::text))),
    CONSTRAINT chk_knowledge_libraries_source_type CHECK (((source_type)::text ~ '^[A-Z][A-Z0-9_]*$'::text)),
    CONSTRAINT chk_knowledge_libraries_upload_without_source CHECK ((((source_type)::text <> 'UPLOAD'::text) OR ((source_path IS NULL) AND (source_url IS NULL) AND (source_credentials IS NULL) AND (source_settings IS NULL))))
);

CREATE TABLE library_folders (
    id uuid NOT NULL,
    library_id uuid NOT NULL,
    parent_folder_id uuid,
    name character varying(255) NOT NULL,
    organization_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE library_maintenance_skips (
    library_id uuid NOT NULL,
    maintenance character varying(32) NOT NULL,
    skipped_documents bigint NOT NULL,
    recorded_at timestamp with time zone NOT NULL
);

CREATE TABLE library_metadata_field_values (
    id uuid NOT NULL,
    field_id uuid NOT NULL,
    code character varying(50) NOT NULL,
    label character varying(100) NOT NULL,
    sort_order integer NOT NULL,
    CONSTRAINT chk_library_metadata_field_values_code CHECK (((code)::text ~ '^[A-Za-z0-9][A-Za-z0-9_.-]*$'::text))
);

CREATE TABLE library_metadata_fields (
    id uuid NOT NULL,
    library_id uuid NOT NULL,
    field_key character varying(50) NOT NULL,
    label character varying(100) NOT NULL,
    field_type character varying(20) NOT NULL,
    value_pattern character varying(200),
    filter_enabled boolean DEFAULT false NOT NULL,
    context_prefix_enabled boolean DEFAULT false NOT NULL,
    sort_order integer NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT chk_library_metadata_fields_key CHECK (((field_key)::text ~ '^[a-z][a-z0-9_]*$'::text)),
    CONSTRAINT chk_library_metadata_fields_pattern CHECK ((((field_type)::text = 'PATTERN'::text) = (value_pattern IS NOT NULL))),
    CONSTRAINT chk_library_metadata_fields_retrieval_effect CHECK ((filter_enabled OR context_prefix_enabled)),
    CONSTRAINT chk_library_metadata_fields_type CHECK ((field_type IN ('SELECT', 'DATE', 'PATTERN')))
);

CREATE TABLE library_metadata_schema_changes (
    id uuid NOT NULL,
    field_id uuid NOT NULL,
    value_id uuid,
    target_value_id uuid,
    change_kind character varying(20) NOT NULL,
    correlation_ref character varying(100) NOT NULL,
    requested_at timestamp with time zone DEFAULT now() NOT NULL,
    processed_documents bigint DEFAULT 0 NOT NULL,
    CONSTRAINT chk_library_metadata_schema_changes_kind CHECK ((change_kind IN ('VALUE_REMAP', 'FIELD_DELETION'))),
    CONSTRAINT chk_library_metadata_schema_changes_processed CHECK ((processed_documents >= 0)),
    CONSTRAINT chk_library_metadata_schema_changes_subject CHECK ((((change_kind)::text = 'VALUE_REMAP'::text) = (value_id IS NOT NULL))),
    CONSTRAINT chk_library_metadata_schema_changes_target CHECK (((target_value_id IS NULL) OR (((change_kind)::text = 'VALUE_REMAP'::text) AND (target_value_id <> value_id))))
);

CREATE TABLE llm_model_seed_marker (
    id integer NOT NULL,
    seeded_at timestamp with time zone NOT NULL,
    CONSTRAINT chk_llm_model_seed_marker_singleton CHECK ((id = 1))
);

CREATE TABLE llm_models (
    id uuid NOT NULL,
    display_name character varying(120) NOT NULL,
    base_url character varying(500) NOT NULL,
    model_identifier character varying(200) NOT NULL,
    temperature numeric(3,2),
    max_tokens integer DEFAULT 2000 NOT NULL,
    api_key_ciphertext text,
    active boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    tool_capability character varying(20) DEFAULT 'UNVERIFIED'::character varying NOT NULL,
    tool_capability_basis character varying(20),
    tool_capability_assessed_at timestamp with time zone,
    tool_capability_measurement_run character varying(300),
    CONSTRAINT chk_llm_models_max_tokens CHECK ((max_tokens > 0)),
    CONSTRAINT chk_llm_models_temperature CHECK (((temperature >= (0)::numeric) AND (temperature <= (2)::numeric))),
    CONSTRAINT chk_llm_models_tool_capability CHECK ((tool_capability IN ('UNVERIFIED', 'CAPABLE', 'NOT_CAPABLE'))),
    CONSTRAINT chk_llm_models_tool_capability_basis CHECK ((tool_capability_basis IN ('MEASURED', 'MANUAL'))),
    CONSTRAINT chk_llm_models_tool_capability_statement CHECK (((((tool_capability)::text = 'UNVERIFIED'::text) AND (tool_capability_basis IS NULL) AND (tool_capability_assessed_at IS NULL) AND (tool_capability_measurement_run IS NULL)) OR (((tool_capability)::text <> 'UNVERIFIED'::text) AND (tool_capability_basis IS NOT NULL) AND (tool_capability_assessed_at IS NOT NULL) AND (((tool_capability_basis)::text = 'MEASURED'::text) = (tool_capability_measurement_run IS NOT NULL)))))
);

CREATE TABLE metadata_model_extraction_stats (
    library_id uuid NOT NULL,
    calls bigint DEFAULT 0 NOT NULL,
    accepted_values bigint DEFAULT 0 NOT NULL,
    rejected_below_threshold bigint DEFAULT 0 CONSTRAINT metadata_model_extraction_sta_rejected_below_threshold_not_null NOT NULL,
    rejected_outside_vocabulary bigint DEFAULT 0 CONSTRAINT metadata_model_extraction_s_rejected_outside_vocabular_not_null NOT NULL,
    failures bigint DEFAULT 0 NOT NULL,
    rejected_pool_full bigint DEFAULT 0 NOT NULL,
    keywords_assigned bigint DEFAULT 0 NOT NULL,
    last_call_at timestamp with time zone
);

CREATE TABLE metadata_model_rejections (
    id uuid NOT NULL,
    library_id uuid NOT NULL,
    document_id uuid,
    field_key character varying(100) NOT NULL,
    proposed_value character varying(200),
    confidence double precision,
    reason character varying(30) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_metadata_model_rejections_reason CHECK ((reason IN ('BELOW_THRESHOLD', 'OUTSIDE_VOCABULARY')))
);

CREATE TABLE model_role_observations (
    role character varying(32) NOT NULL,
    base_url text NOT NULL,
    model text NOT NULL,
    reachable boolean NOT NULL,
    timed_out boolean NOT NULL,
    diagnostic text,
    observed_at timestamp with time zone NOT NULL
);

CREATE TABLE private_storage_quota_settings (
    id integer NOT NULL,
    quota_bytes bigint NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT chk_private_storage_quota_settings_bytes CHECK ((quota_bytes >= 0)),
    CONSTRAINT chk_private_storage_quota_settings_singleton CHECK ((id = 1))
);

CREATE TABLE source_event_batches (
    id uuid NOT NULL,
    library_id uuid NOT NULL,
    source_type character varying(20) NOT NULL,
    keys text[] DEFAULT '{}'::text[] NOT NULL,
    dropped integer DEFAULT 0 NOT NULL,
    overflowed boolean DEFAULT false NOT NULL,
    deferrals integer DEFAULT 0 NOT NULL,
    due_at timestamp with time zone NOT NULL,
    claimed_until timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_source_event_batches_counts CHECK (((dropped >= 0) AND (deferrals >= 0)))
);

CREATE TABLE source_sync_presence (
    document_id uuid NOT NULL,
    sync_state_id uuid NOT NULL,
    scan_id uuid NOT NULL
);

CREATE TABLE source_sync_revisits (
    id uuid NOT NULL,
    sync_state_id uuid NOT NULL,
    container_key text NOT NULL,
    hierarchy_path text,
    created_at timestamp with time zone NOT NULL
);

CREATE TABLE source_sync_state (
    id uuid NOT NULL,
    library_id uuid NOT NULL,
    full_sync_job_id uuid,
    completed_scope_keys text,
    full_sync_completed_at timestamp with time zone,
    incremental_anchor timestamp with time zone,
    updated_at timestamp with time zone NOT NULL,
    change_cursors jsonb,
    subtree_markers jsonb,
    scan_progress jsonb,
    settings_basis character varying(64),
    full_sync_quota_held boolean,
    resolved_scopes jsonb
);

ALTER TABLE ONLY chunk_full_text
    ADD CONSTRAINT chunk_full_text_pkey PRIMARY KEY (chunk_id);

ALTER TABLE ONLY document_keywords
    ADD CONSTRAINT document_keywords_pkey PRIMARY KEY (id);

ALTER TABLE ONLY document_metadata_values
    ADD CONSTRAINT document_metadata_values_pkey PRIMARY KEY (id);

ALTER TABLE ONLY document_type_suffix_exclusions
    ADD CONSTRAINT document_type_suffix_exclusions_pkey PRIMARY KEY (code, token);

ALTER TABLE ONLY document_type_suffixes
    ADD CONSTRAINT document_type_suffixes_pkey PRIMARY KEY (code, suffix);

ALTER TABLE ONLY document_type_synonyms
    ADD CONSTRAINT document_type_synonyms_pkey PRIMARY KEY (code, synonym);

ALTER TABLE ONLY document_type_vocabulary
    ADD CONSTRAINT document_type_vocabulary_pkey PRIMARY KEY (code);

ALTER TABLE ONLY documents
    ADD CONSTRAINT documents_pkey PRIMARY KEY (id);

ALTER TABLE ONLY embedding_index
    ADD CONSTRAINT embedding_index_pkey PRIMARY KEY (id);

ALTER TABLE ONLY embedding_rate_measurements
    ADD CONSTRAINT embedding_rate_measurements_pkey PRIMARY KEY (embedding_model);

ALTER TABLE ONLY indexing_jobs
    ADD CONSTRAINT indexing_jobs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY indexing_run_events
    ADD CONSTRAINT indexing_run_events_pkey PRIMARY KEY (id);

ALTER TABLE ONLY knowledge_libraries
    ADD CONSTRAINT knowledge_libraries_pkey PRIMARY KEY (id);

ALTER TABLE ONLY library_folders
    ADD CONSTRAINT library_folders_pkey PRIMARY KEY (id);

ALTER TABLE ONLY library_maintenance_skips
    ADD CONSTRAINT library_maintenance_skips_pkey PRIMARY KEY (library_id, maintenance);

ALTER TABLE ONLY library_metadata_field_values
    ADD CONSTRAINT library_metadata_field_values_pkey PRIMARY KEY (id);

ALTER TABLE ONLY library_metadata_fields
    ADD CONSTRAINT library_metadata_fields_pkey PRIMARY KEY (id);

ALTER TABLE ONLY library_metadata_schema_changes
    ADD CONSTRAINT library_metadata_schema_changes_pkey PRIMARY KEY (id);

ALTER TABLE ONLY llm_model_seed_marker
    ADD CONSTRAINT llm_model_seed_marker_pkey PRIMARY KEY (id);

ALTER TABLE ONLY llm_models
    ADD CONSTRAINT llm_models_pkey PRIMARY KEY (id);

ALTER TABLE ONLY metadata_model_extraction_stats
    ADD CONSTRAINT metadata_model_extraction_stats_pkey PRIMARY KEY (library_id);

ALTER TABLE ONLY metadata_model_rejections
    ADD CONSTRAINT metadata_model_rejections_pkey PRIMARY KEY (id);

ALTER TABLE ONLY model_role_observations
    ADD CONSTRAINT model_role_observations_pkey PRIMARY KEY (role);

ALTER TABLE ONLY private_storage_quota_settings
    ADD CONSTRAINT private_storage_quota_settings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY source_event_batches
    ADD CONSTRAINT source_event_batches_pkey PRIMARY KEY (id);

ALTER TABLE ONLY source_sync_presence
    ADD CONSTRAINT source_sync_presence_pkey PRIMARY KEY (document_id);

ALTER TABLE ONLY source_sync_revisits
    ADD CONSTRAINT source_sync_revisits_pkey PRIMARY KEY (id);

ALTER TABLE ONLY source_sync_state
    ADD CONSTRAINT source_sync_state_pkey PRIMARY KEY (id);

ALTER TABLE ONLY documents
    ADD CONSTRAINT uk_documents_id_organization UNIQUE (id, organization_id);

ALTER TABLE ONLY documents
    ADD CONSTRAINT uk_documents_library_path UNIQUE (library_id, file_path);

ALTER TABLE ONLY knowledge_libraries
    ADD CONSTRAINT uk_knowledge_libraries_id_organization UNIQUE (id, organization_id);

ALTER TABLE ONLY library_folders
    ADD CONSTRAINT uk_library_folders_id_library_organization UNIQUE (id, library_id, organization_id);

ALTER TABLE ONLY library_metadata_field_values
    ADD CONSTRAINT uk_library_metadata_field_values_id_field UNIQUE (id, field_id);

ALTER TABLE ONLY library_metadata_schema_changes
    ADD CONSTRAINT uk_library_metadata_schema_changes_subject UNIQUE NULLS NOT DISTINCT (field_id, value_id);

ALTER TABLE ONLY source_sync_state
    ADD CONSTRAINT uk_source_sync_state_library UNIQUE (library_id);

ALTER TABLE ONLY document_keywords
    ADD CONSTRAINT uq_document_keywords_document_keyword UNIQUE (document_id, keyword);

CREATE INDEX idx_chunk_full_text_content_tsv ON chunk_full_text USING gin (content_tsv);

CREATE INDEX idx_chunk_full_text_document_id ON chunk_full_text USING btree (document_id);

CREATE INDEX idx_chunk_full_text_library_id ON chunk_full_text USING btree (library_id);

CREATE INDEX idx_document_keywords_document ON document_keywords USING btree (document_id);

CREATE INDEX idx_document_metadata_values_library_field ON document_metadata_values USING btree (library_field_id);

CREATE INDEX idx_document_metadata_values_library_value ON document_metadata_values USING btree (library_value_id);

CREATE INDEX idx_document_metadata_values_vocabulary_code ON document_metadata_values USING btree (vocabulary_code);

CREATE INDEX idx_documents_folder ON documents USING btree (folder_id);

CREATE INDEX idx_documents_indexed_chunk_count ON documents USING btree (organization_id, chunk_count) WHERE ((status)::text = 'INDEXED'::text);

CREATE INDEX idx_documents_library_context_prefix ON documents USING btree (library_id) WHERE (context_prefix_stamp IS NULL);

CREATE INDEX idx_documents_library_status ON documents USING btree (library_id, status);

CREATE INDEX idx_documents_parent ON documents USING btree (parent_document_id);

CREATE INDEX idx_documents_pending_instance ON documents USING btree (processing_instance_id) WHERE ((status)::text = 'PENDING'::text);

CREATE INDEX idx_indexing_jobs_library_completed ON indexing_jobs USING btree (library_id, completed_at) WHERE ((status)::text = 'COMPLETED'::text);
CREATE INDEX idx_indexing_jobs_organization_id ON indexing_jobs USING btree (organization_id);

CREATE INDEX idx_indexing_run_events_job_id ON indexing_run_events USING btree (job_id);

CREATE INDEX idx_knowledge_libraries_external_access_expiry ON knowledge_libraries USING btree (external_access_expires_at) WHERE ((external_access_state)::text = 'ACTIVE'::text);

CREATE INDEX idx_library_folders_parent ON library_folders USING btree (parent_folder_id);

CREATE INDEX idx_metadata_model_rejections_library_created ON metadata_model_rejections USING btree (library_id, created_at DESC);

CREATE INDEX idx_source_event_batches_due_at ON source_event_batches USING btree (due_at);

CREATE INDEX idx_source_sync_presence_state ON source_sync_presence USING btree (sync_state_id, scan_id);

CREATE INDEX idx_source_sync_revisits_state ON source_sync_revisits USING btree (sync_state_id);

CREATE UNIQUE INDEX uk_document_metadata_values_document_field ON document_metadata_values USING btree (document_id, field_key);

CREATE UNIQUE INDEX uk_documents_library_checksum ON documents USING btree (library_id, checksum) WHERE ((checksum IS NOT NULL) AND ((source_type)::text = 'UPLOAD'::text) AND (parent_document_id IS NULL));

CREATE UNIQUE INDEX uk_indexing_jobs_library_running ON indexing_jobs USING btree (library_id) WHERE ((status)::text = 'RUNNING'::text);

CREATE UNIQUE INDEX uk_library_folders_child_name ON library_folders USING btree (library_id, parent_folder_id, name) WHERE (parent_folder_id IS NOT NULL);

CREATE UNIQUE INDEX uk_library_folders_root_name ON library_folders USING btree (library_id, name) WHERE (parent_folder_id IS NULL);

CREATE UNIQUE INDEX uk_library_metadata_field_values_code ON library_metadata_field_values USING btree (field_id, code);


CREATE UNIQUE INDEX uk_library_metadata_fields_key ON library_metadata_fields USING btree (library_id, field_key);

CREATE UNIQUE INDEX uk_source_event_batches_open ON source_event_batches USING btree (library_id) WHERE (claimed_until IS NULL);

CREATE UNIQUE INDEX ux_llm_models_single_active ON llm_models USING btree (active) WHERE active;

CREATE TRIGGER trg_embedding_index_invalidation AFTER INSERT OR DELETE OR UPDATE ON embedding_index FOR EACH ROW EXECUTE FUNCTION notify_cache_invalidation('embedding-index', 'run_status', 'run_started_at', 'run_started_by', 'run_organization_id', 'run_finished_at', 'run_message', 'updated_at');

CREATE TRIGGER trg_knowledge_libraries_guard_owner_only_release BEFORE INSERT OR UPDATE OF external_access_state ON knowledge_libraries FOR EACH ROW EXECUTE FUNCTION knowledge_libraries_guard_owner_only_release();

CREATE TRIGGER trg_llm_models_invalidation AFTER INSERT OR DELETE OR UPDATE OR TRUNCATE ON llm_models FOR EACH STATEMENT EXECUTE FUNCTION notify_cache_invalidation('chat-model');

CREATE TRIGGER trg_model_role_observations_invalidation AFTER INSERT OR DELETE OR UPDATE ON model_role_observations FOR EACH ROW EXECUTE FUNCTION notify_cache_invalidation('model-role-observations', 'observed_at');

ALTER TABLE ONLY document_keywords
    ADD CONSTRAINT fk_document_keywords_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE;

ALTER TABLE ONLY document_keywords
    ADD CONSTRAINT fk_document_keywords_library FOREIGN KEY (library_id) REFERENCES knowledge_libraries(id) ON DELETE CASCADE;

ALTER TABLE ONLY document_metadata_values
    ADD CONSTRAINT fk_document_metadata_values_actor FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE ONLY document_metadata_values
    ADD CONSTRAINT fk_document_metadata_values_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE;

ALTER TABLE ONLY document_metadata_values
    ADD CONSTRAINT fk_document_metadata_values_library_field FOREIGN KEY (library_field_id) REFERENCES library_metadata_fields(id) ON DELETE CASCADE;

ALTER TABLE ONLY document_metadata_values
    ADD CONSTRAINT fk_document_metadata_values_library_value FOREIGN KEY (library_value_id, library_field_id) REFERENCES library_metadata_field_values(id, field_id) ON DELETE RESTRICT;

ALTER TABLE ONLY document_metadata_values
    ADD CONSTRAINT fk_document_metadata_values_vocabulary FOREIGN KEY (vocabulary_code) REFERENCES document_type_vocabulary(code) ON DELETE RESTRICT;

ALTER TABLE ONLY document_type_suffix_exclusions
    ADD CONSTRAINT fk_document_type_suffix_exclusions_code FOREIGN KEY (code) REFERENCES document_type_vocabulary(code) ON DELETE CASCADE;

ALTER TABLE ONLY document_type_suffixes
    ADD CONSTRAINT fk_document_type_suffixes_code FOREIGN KEY (code) REFERENCES document_type_vocabulary(code) ON DELETE CASCADE;

ALTER TABLE ONLY document_type_synonyms
    ADD CONSTRAINT fk_document_type_synonyms_code FOREIGN KEY (code) REFERENCES document_type_vocabulary(code) ON DELETE CASCADE;

ALTER TABLE ONLY documents
    ADD CONSTRAINT fk_documents_folder FOREIGN KEY (folder_id, library_id, organization_id) REFERENCES library_folders(id, library_id, organization_id) ON DELETE RESTRICT;

ALTER TABLE ONLY documents
    ADD CONSTRAINT fk_documents_library_organization FOREIGN KEY (library_id, organization_id) REFERENCES knowledge_libraries(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE ONLY documents
    ADD CONSTRAINT fk_documents_parent FOREIGN KEY (parent_document_id, organization_id) REFERENCES documents(id, organization_id);

ALTER TABLE ONLY documents
    ADD CONSTRAINT fk_documents_uploaded_by_user_organization FOREIGN KEY (uploaded_by_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE SET NULL (uploaded_by_user_id);

ALTER TABLE ONLY indexing_jobs
    ADD CONSTRAINT fk_indexing_jobs_library_organization FOREIGN KEY (library_id, organization_id) REFERENCES knowledge_libraries(id, organization_id) ON DELETE SET NULL (library_id);

ALTER TABLE ONLY indexing_jobs
    ADD CONSTRAINT fk_indexing_jobs_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT;

ALTER TABLE ONLY indexing_run_events
    ADD CONSTRAINT fk_indexing_run_events_job FOREIGN KEY (job_id) REFERENCES indexing_jobs(id) ON DELETE CASCADE;

ALTER TABLE ONLY knowledge_libraries
    ADD CONSTRAINT fk_knowledge_libraries_asset FOREIGN KEY (id, organization_id) REFERENCES assets(id, organization_id) ON DELETE CASCADE;

ALTER TABLE ONLY knowledge_libraries
    ADD CONSTRAINT fk_knowledge_libraries_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT;

ALTER TABLE ONLY library_folders
    ADD CONSTRAINT fk_library_folders_library FOREIGN KEY (library_id, organization_id) REFERENCES knowledge_libraries(id, organization_id) ON DELETE CASCADE;

ALTER TABLE ONLY library_folders
    ADD CONSTRAINT fk_library_folders_parent FOREIGN KEY (parent_folder_id, library_id, organization_id) REFERENCES library_folders(id, library_id, organization_id) ON DELETE RESTRICT;

ALTER TABLE ONLY library_maintenance_skips
    ADD CONSTRAINT fk_library_maintenance_skips_library FOREIGN KEY (library_id) REFERENCES knowledge_libraries(id) ON DELETE CASCADE;

ALTER TABLE ONLY library_metadata_field_values
    ADD CONSTRAINT fk_library_metadata_field_values_field FOREIGN KEY (field_id) REFERENCES library_metadata_fields(id) ON DELETE CASCADE;

ALTER TABLE ONLY library_metadata_fields
    ADD CONSTRAINT fk_library_metadata_fields_library FOREIGN KEY (library_id) REFERENCES knowledge_libraries(id) ON DELETE CASCADE;

ALTER TABLE ONLY library_metadata_schema_changes
    ADD CONSTRAINT fk_library_metadata_schema_changes_field FOREIGN KEY (field_id) REFERENCES library_metadata_fields(id) ON DELETE CASCADE;

ALTER TABLE ONLY library_metadata_schema_changes
    ADD CONSTRAINT fk_library_metadata_schema_changes_target FOREIGN KEY (target_value_id, field_id) REFERENCES library_metadata_field_values(id, field_id) ON DELETE RESTRICT;

ALTER TABLE ONLY library_metadata_schema_changes
    ADD CONSTRAINT fk_library_metadata_schema_changes_value FOREIGN KEY (value_id, field_id) REFERENCES library_metadata_field_values(id, field_id) ON DELETE CASCADE;

ALTER TABLE ONLY metadata_model_extraction_stats
    ADD CONSTRAINT fk_metadata_model_extraction_stats_library FOREIGN KEY (library_id) REFERENCES knowledge_libraries(id) ON DELETE CASCADE;

ALTER TABLE ONLY metadata_model_rejections
    ADD CONSTRAINT fk_metadata_model_rejections_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE SET NULL;

ALTER TABLE ONLY metadata_model_rejections
    ADD CONSTRAINT fk_metadata_model_rejections_library FOREIGN KEY (library_id) REFERENCES knowledge_libraries(id) ON DELETE CASCADE;

ALTER TABLE ONLY source_sync_presence
    ADD CONSTRAINT fk_source_sync_presence_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE;

ALTER TABLE ONLY source_sync_presence
    ADD CONSTRAINT fk_source_sync_presence_state FOREIGN KEY (sync_state_id) REFERENCES source_sync_state(id) ON DELETE CASCADE;

ALTER TABLE ONLY source_sync_revisits
    ADD CONSTRAINT fk_source_sync_revisits_state FOREIGN KEY (sync_state_id) REFERENCES source_sync_state(id) ON DELETE CASCADE;

ALTER TABLE ONLY source_sync_state
    ADD CONSTRAINT fk_source_sync_state_library FOREIGN KEY (library_id) REFERENCES knowledge_libraries(id) ON DELETE CASCADE;

-- Delivered Dokumentart vocabulary with its synonyms, endings and exclusions. An
-- installation extends this by inserting rows, never by editing the baseline; the
-- delivered set itself is reconciled against TestVocabularies by
-- DocumentTypeVocabularySeedReconciliationTest.
INSERT INTO document_type_vocabulary (code, label, sort_order) VALUES
    ('SATZUNG_ORDNUNG', 'Satzung/Ordnung', 10),
    ('DIENSTANWEISUNG', 'Dienstanweisung', 20),
    ('VERMERK', 'Vermerk', 30),
    ('PROTOKOLL', 'Protokoll', 40),
    ('BESCHEID_VORLAGE', 'Bescheid-Vorlage', 50),
    ('FORMULAR', 'Formular', 60),
    ('GEBUEHRENVERZEICHNIS', 'Gebührenverzeichnis', 70),
    ('PRAESENTATION', 'Präsentation', 80),
    ('SONSTIGES', 'Sonstiges', 90);

INSERT INTO document_type_synonyms (code, synonym) VALUES
    ('SATZUNG_ORDNUNG', 'satzung'),
    ('SATZUNG_ORDNUNG', 'satzungen'),
    ('SATZUNG_ORDNUNG', 'ordnung'),
    ('SATZUNG_ORDNUNG', 'hauptsatzung'),
    ('SATZUNG_ORDNUNG', 'gebuehrensatzung'),
    ('SATZUNG_ORDNUNG', 'gebuehrenordnung'),
    ('SATZUNG_ORDNUNG', 'benutzungsordnung'),
    ('DIENSTANWEISUNG', 'dienstanweisung'),
    ('DIENSTANWEISUNG', 'dienstanweisungen'),
    ('DIENSTANWEISUNG', 'dienstanordnung'),
    ('VERMERK', 'vermerk'),
    ('VERMERK', 'aktenvermerk'),
    ('PROTOKOLL', 'protokoll'),
    ('PROTOKOLL', 'niederschrift'),
    ('PROTOKOLL', 'sitzungsprotokoll'),
    ('PROTOKOLL', 'ergebnisprotokoll'),
    ('BESCHEID_VORLAGE', 'bescheid'),
    ('BESCHEID_VORLAGE', 'bescheidvorlage'),
    ('BESCHEID_VORLAGE', 'bescheidmuster'),
    ('BESCHEID_VORLAGE', 'musterbescheid'),
    ('FORMULAR', 'formular'),
    ('FORMULAR', 'formulare'),
    ('FORMULAR', 'antragsformular'),
    ('FORMULAR', 'formblatt'),
    ('FORMULAR', 'vordruck'),
    ('GEBUEHRENVERZEICHNIS', 'gebuehrenverzeichnis'),
    ('GEBUEHRENVERZEICHNIS', 'gebuehrentabelle'),
    ('GEBUEHRENVERZEICHNIS', 'gebuehrentarif'),
    ('GEBUEHRENVERZEICHNIS', 'kostenverzeichnis'),
    ('PRAESENTATION', 'praesentation'),
    ('PRAESENTATION', 'vortrag'),
    ('PRAESENTATION', 'folien'),
    ('SONSTIGES', 'sonstiges'),
    ('SONSTIGES', 'sonstige');
INSERT INTO document_type_suffixes (code, suffix, min_prefix_length) VALUES
    ('SATZUNG_ORDNUNG', 'satzung', 3),
    ('SATZUNG_ORDNUNG', 'ordnung', 3),
    ('DIENSTANWEISUNG', 'dienstanweisung', 3),
    ('GEBUEHRENVERZEICHNIS', 'gebuehrenverzeichnis', 3),
    ('PROTOKOLL', 'protokoll', 3),
    ('FORMULAR', 'formular', 3),
    ('VERMERK', 'vermerk', 3);

INSERT INTO document_type_suffix_exclusions (code, token) VALUES
    ('SATZUNG_ORDNUNG', 'anordnung'),
    ('SATZUNG_ORDNUNG', 'zuordnung'),
    ('SATZUNG_ORDNUNG', 'neuzuordnung'),
    ('SATZUNG_ORDNUNG', 'einordnung'),
    ('SATZUNG_ORDNUNG', 'neuordnung'),
    ('SATZUNG_ORDNUNG', 'umordnung'),
    ('SATZUNG_ORDNUNG', 'unterordnung'),
    ('SATZUNG_ORDNUNG', 'rangordnung'),
    ('SATZUNG_ORDNUNG', 'sitzordnung'),
    ('SATZUNG_ORDNUNG', 'tagesordnung'),
    ('SATZUNG_ORDNUNG', 'groessenordnung'),
    ('VERMERK', 'sperrvermerk'),
    ('VERMERK', 'sichtvermerk'),
    ('VERMERK', 'eingangsvermerk');

-- The model roles and settings of the installation (ADR-0060), each one row at most.

-- The installation state of the whole installation, one row for every instance (ADR-0060,
-- Entscheidung 4). No row means pending: the first start writes it under the start-up lock. The
-- token is kept only as its SHA-256 hash and is cleared with the completion.
CREATE TABLE installation_state (
    id smallint NOT NULL,
    status character varying(20) NOT NULL,
    token_hash character varying(64),
    token_source character varying(20),
    completion_source character varying(30),
    completed_at timestamp with time zone,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT installation_state_pkey PRIMARY KEY (id),
    CONSTRAINT chk_installation_state_singleton CHECK ((id = 1))
);

-- The saved steps of a pending installation (ADR-0060, Entscheidung 6), one row per step. payload
-- holds the values without secrets; secret holds the password hash of the first account or the
-- encrypted model key. Every row is deleted with the completion.
CREATE TABLE installation_steps (
    step character varying(30) NOT NULL,
    payload jsonb NOT NULL,
    secret text,
    check_succeeded boolean,
    check_message character varying(1000),
    checked_at timestamp with time zone,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT installation_steps_pkey PRIMARY KEY (step)
);

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
    generation bigint DEFAULT 1 NOT NULL,
    reference_vectors real[],
    CONSTRAINT embedding_model_pkey PRIMARY KEY (id),
    CONSTRAINT chk_embedding_model_singleton CHECK ((id = 1)),
    CONSTRAINT chk_embedding_model_max_input_tokens CHECK ((max_input_tokens > 0)),
    CONSTRAINT chk_embedding_model_dimensions CHECK ((dimensions > 0))
);

CREATE TRIGGER trg_embedding_model_invalidation AFTER INSERT OR DELETE OR UPDATE OR TRUNCATE ON embedding_model FOR EACH STATEMENT EXECUTE FUNCTION notify_cache_invalidation('embedding-model');

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
    vocabulary character varying(500),
    realtime_protocol character varying(16),
    realtime_url character varying(500),
    realtime_model character varying(200),
    realtime_api_key_ciphertext text,
    realtime_max_sessions integer,
    CONSTRAINT transcription_model_pkey PRIMARY KEY (id),
    CONSTRAINT chk_transcription_model_singleton CHECK ((id = 1))
);

CREATE TRIGGER trg_transcription_model_invalidation AFTER INSERT OR DELETE OR UPDATE OR TRUNCATE ON transcription_model FOR EACH STATEMENT EXECUTE FUNCTION notify_cache_invalidation('transcription-model');

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

-- The malware scan of the installation, one row once the installation or the administration saves
-- it; without a row the scan is off. host and port address the clamd daemon of the operator,
-- timeout_seconds bounds one whole scan, on_unavailable (REJECT or ACCEPT) decides about a file the
-- scanner could not judge.
CREATE TABLE malware_scan_settings (
    id smallint NOT NULL,
    enabled boolean NOT NULL,
    host character varying(255),
    port integer NOT NULL,
    timeout_seconds integer NOT NULL,
    on_unavailable character varying(16) NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT malware_scan_settings_pkey PRIMARY KEY (id),
    CONSTRAINT chk_malware_scan_settings_singleton CHECK ((id = 1))
);

-- The installation-wide rhythm of the full sync per source type as the system administration set
-- it; without a row the factory default of the connector applies. A library may set its own.
CREATE TABLE full_sync_rhythm_settings (
    source_type character varying(20) NOT NULL,
    interval_days integer NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT full_sync_rhythm_settings_pkey PRIMARY KEY (source_type),
    CONSTRAINT chk_full_sync_rhythm_settings_days CHECK (((interval_days >= 1) AND (interval_days <= 365)))
);

-- The house-wide storage quota per shared library as the system administration set it, at most the
-- operator's upper limit (opaa.library.quota-bytes); without the row the upper limit applies alone.
CREATE TABLE shared_library_quota_settings (
    id integer NOT NULL,
    quota_bytes bigint NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT shared_library_quota_settings_pkey PRIMARY KEY (id),
    CONSTRAINT chk_shared_library_quota_settings_bytes CHECK ((quota_bytes > 0)),
    CONSTRAINT chk_shared_library_quota_settings_singleton CHECK ((id = 1))
);

-- The generation of an embedding model (ADR-0061, Entscheidung 2): vectors are comparable only
-- under the same generation, model name and dimension. Every model change draws a new one from
-- embedding_generation_seq, which never hands out a number twice; an endpoint change keeps it. The
-- first model has generation 1, so the sequence starts after it.

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

SELECT setval('embedding_generation_seq', 1);

-- A person's own vocabulary list for speech input, at most one per account. Visible only to the
-- person, never evaluated, sent as prompt after the installation's list; deleted with the account.
CREATE TABLE transcription_vocabularies (
    user_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    vocabulary character varying(500) NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT transcription_vocabularies_pkey PRIMARY KEY (user_id),
    CONSTRAINT fk_transcription_vocabularies_user_organization FOREIGN KEY (user_id, organization_id)
        REFERENCES users(id, organization_id) ON DELETE CASCADE
);

-- The live transcription sessions open right now across all instances (ADR-0072), one row per
-- session without any reference to the person: the cap of the installation counts them. A row is
-- deleted when its session ends; one left behind by a stopped instance no longer counts once it is
-- older than the hard limit of a session plus a margin, and is removed by the next opening.
CREATE TABLE transcription_realtime_slots (
    id uuid NOT NULL,
    opened_at timestamp with time zone NOT NULL,
    CONSTRAINT transcription_realtime_slots_pkey PRIMARY KEY (id)
);

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
