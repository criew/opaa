SET LOCAL lock_timeout = '5s';

-- Agents: an asset type on the shell with its instruction, assigned libraries and tools,
-- plan step and limits; the installation-wide switch with the tools switched on as a single row;
-- and per organization the record that the delivered agent "Recherche" was provisioned, so
-- deleting it never brings it back. seed_key marks that delivered agent.

CREATE TABLE agents (
    id uuid NOT NULL,
    asset_type character varying(30) DEFAULT 'AGENT'::character varying NOT NULL,
    organization_id uuid NOT NULL,
    instruction text DEFAULT ''::text NOT NULL,
    plan_step character varying(20) DEFAULT 'OFF'::character varying NOT NULL,
    max_tool_calls integer,
    max_search_calls integer,
    max_duration_seconds integer,
    max_collected_chars integer,
    revision integer DEFAULT 1 NOT NULL,
    seed_key character varying(40),
    CONSTRAINT chk_agents_asset_type CHECK (((asset_type)::text = 'AGENT'::text)),
    CONSTRAINT chk_agents_plan_step CHECK ((plan_step IN ('OFF', 'CONFIRM'))),
    CONSTRAINT chk_agents_instruction_length CHECK ((char_length(instruction) <= 8000)),
    CONSTRAINT chk_agents_limits_positive CHECK ((((max_tool_calls IS NULL) OR (max_tool_calls >= 1)) AND ((max_search_calls IS NULL) OR (max_search_calls >= 1)) AND ((max_duration_seconds IS NULL) OR (max_duration_seconds >= 1)) AND ((max_collected_chars IS NULL) OR (max_collected_chars >= 1)))),
    CONSTRAINT chk_agents_revision_positive CHECK ((revision >= 1)),
    CONSTRAINT chk_agents_seed_key_format CHECK (((seed_key IS NULL) OR ((seed_key)::text ~ '^[a-z][a-z0-9_]*$'::text))),
    CONSTRAINT agents_pkey PRIMARY KEY (id),
    CONSTRAINT fk_agents_asset FOREIGN KEY (id, asset_type, organization_id) REFERENCES assets(id, asset_type, organization_id) ON DELETE CASCADE
);

-- A library is named by id only: deleting or locking it leaves the agent usable and its page
-- says that one library is gone, rather than the assignment vanishing silently.
CREATE TABLE agent_libraries (
    agent_id uuid NOT NULL,
    library_id uuid NOT NULL,
    CONSTRAINT agent_libraries_pkey PRIMARY KEY (agent_id, library_id),
    CONSTRAINT fk_agent_libraries_agent FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE
);

CREATE TABLE agent_tools (
    agent_id uuid NOT NULL,
    tool_name character varying(64) NOT NULL,
    CONSTRAINT agent_tools_pkey PRIMARY KEY (agent_id, tool_name),
    CONSTRAINT chk_agent_tools_name_format CHECK (((tool_name)::text ~ '^[a-z][a-z0-9_]*$'::text)),
    CONSTRAINT fk_agent_tools_agent FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE
);

CREATE TABLE agent_settings (
    id integer NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT agent_settings_pkey PRIMARY KEY (id),
    CONSTRAINT chk_agent_settings_singleton CHECK ((id = 1)),
    CONSTRAINT fk_agent_settings_updated_by FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE SET NULL
);

INSERT INTO agent_settings (id) VALUES (1);

-- The tools the installation switched on; a run is offered only these. Delivered: every tool of
-- the first cut except run_code, which needs the sandbox to be set up.
CREATE TABLE agent_settings_tools (
    settings_id integer NOT NULL,
    tool_name character varying(64) NOT NULL,
    CONSTRAINT agent_settings_tools_pkey PRIMARY KEY (settings_id, tool_name),
    CONSTRAINT chk_agent_settings_tools_name_format CHECK (((tool_name)::text ~ '^[a-z][a-z0-9_]*$'::text)),
    CONSTRAINT fk_agent_settings_tools_settings FOREIGN KEY (settings_id) REFERENCES agent_settings(id) ON DELETE CASCADE
);

INSERT INTO agent_settings_tools (settings_id, tool_name) VALUES
    (1, 'search_knowledge'), (1, 'read_document'), (1, 'list_sources'), (1, 'ask_user');

CREATE TABLE agent_seeds (
    organization_id uuid NOT NULL,
    seed_key character varying(40) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT agent_seeds_pkey PRIMARY KEY (organization_id, seed_key),
    CONSTRAINT chk_agent_seeds_key_format CHECK (((seed_key)::text ~ '^[a-z][a-z0-9_]*$'::text)),
    CONSTRAINT fk_agent_seeds_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE
);

CREATE INDEX idx_agent_libraries_library ON agent_libraries USING btree (library_id);
