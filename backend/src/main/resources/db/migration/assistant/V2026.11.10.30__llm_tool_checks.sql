SET LOCAL lock_timeout = '5s';

-- Tool checks of chat models: one row per run of the tool-calling case base against a
-- model entry. The cases are fictional; neither the row nor the report names a person.
CREATE TABLE llm_tool_checks (
    id uuid NOT NULL,
    model_id uuid NOT NULL,
    base_url character varying(500) NOT NULL,
    model_identifier character varying(200) NOT NULL,
    status character varying(20) NOT NULL,
    repetitions integer NOT NULL,
    completed_runs integer DEFAULT 0 NOT NULL,
    total_runs integer NOT NULL,
    started_at timestamp with time zone NOT NULL,
    heartbeat_at timestamp with time zone NOT NULL,
    finished_at timestamp with time zone,
    run_id character varying(120),
    failure character varying(500),
    report jsonb,
    report_markdown text,
    CONSTRAINT llm_tool_checks_pkey PRIMARY KEY (id),
    CONSTRAINT fk_llm_tool_checks_model FOREIGN KEY (model_id) REFERENCES llm_models (id) ON DELETE CASCADE,
    CONSTRAINT chk_llm_tool_checks_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'ABORTED')),
    CONSTRAINT chk_llm_tool_checks_repetitions CHECK (repetitions BETWEEN 1 AND 3),
    CONSTRAINT chk_llm_tool_checks_runs CHECK (completed_runs >= 0 AND completed_runs <= total_runs)
);

CREATE INDEX ix_llm_tool_checks_model ON llm_tool_checks (model_id, started_at DESC);

-- At most one check runs per installation: a measurement occupies the model endpoint for minutes.
CREATE UNIQUE INDEX ux_llm_tool_checks_one_running ON llm_tool_checks ((true)) WHERE status = 'RUNNING';
