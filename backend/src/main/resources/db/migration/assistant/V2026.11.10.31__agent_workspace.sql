SET LOCAL lock_timeout = '5s';

-- The workspace in front of the agent loop (ADR-0070, Entscheidung 6). A request locks the one row
-- of agent_workspace, counts the unexpired reservations and adds its own; the lock keeps two
-- requests from overbooking under READ COMMITTED. A reservation expires by the database clock, so
-- the place of a crashed instance frees itself. No person, no agent, no chat: only a run id that
-- appears nowhere else but at the answer in the chat.
CREATE TABLE agent_workspace (
    id smallint PRIMARY KEY CHECK (id = 1)
);

INSERT INTO agent_workspace (id) VALUES (1);

CREATE TABLE agent_run_reservations (
    run_id uuid PRIMARY KEY,
    expires_at timestamptz NOT NULL
);
