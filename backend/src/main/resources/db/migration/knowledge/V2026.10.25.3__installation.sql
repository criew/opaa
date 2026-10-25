SET LOCAL lock_timeout = '5s';

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
