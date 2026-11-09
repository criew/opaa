SET LOCAL lock_timeout = '5s';

-- Whether a chat model calls tools reliably, for its endpoint and model identifier. Every existing
-- row and every row the previous version inserts without naming the columns starts UNVERIFIED,
-- which behaves like NOT_CAPABLE. The basis says how the statement was established (a measurement
-- run or by hand), never who made it; UNVERIFIED carries neither basis, date nor measurement run.
ALTER TABLE llm_models
    ADD COLUMN tool_capability character varying(20) DEFAULT 'UNVERIFIED' NOT NULL,
    ADD COLUMN tool_capability_basis character varying(20),
    ADD COLUMN tool_capability_assessed_at timestamp with time zone,
    ADD COLUMN tool_capability_measurement_run character varying(300),
    ADD CONSTRAINT chk_llm_models_tool_capability
        CHECK (tool_capability IN ('UNVERIFIED', 'CAPABLE', 'NOT_CAPABLE')),
    ADD CONSTRAINT chk_llm_models_tool_capability_basis
        CHECK (tool_capability_basis IN ('MEASURED', 'MANUAL')),
    ADD CONSTRAINT chk_llm_models_tool_capability_statement
        CHECK (
            (tool_capability = 'UNVERIFIED'
                AND tool_capability_basis IS NULL
                AND tool_capability_assessed_at IS NULL
                AND tool_capability_measurement_run IS NULL)
            OR (tool_capability <> 'UNVERIFIED'
                AND tool_capability_basis IS NOT NULL
                AND tool_capability_assessed_at IS NOT NULL
                AND (tool_capability_basis = 'MEASURED')
                    = (tool_capability_measurement_run IS NOT NULL))
        );
