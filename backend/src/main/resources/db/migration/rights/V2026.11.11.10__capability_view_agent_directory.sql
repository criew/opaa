SET LOCAL lock_timeout = '5s';

-- The right to read the agent directory joins the closed list of capabilities (ADR-0036,
-- Entscheidung 5). Each list only gains a value, so the previous release keeps writing.
ALTER TABLE capability_grants DROP CONSTRAINT chk_capability_grants_capability;
ALTER TABLE capability_grants ADD CONSTRAINT chk_capability_grants_capability CHECK ((capability IN (
    'CREATE_SPACE', 'CREATE_LIBRARY', 'CREATE_CONNECTOR_LIBRARY', 'CREATE_INTERNAL_GROUP',
    'CREATE_PROMPT_LIBRARY', 'CREATE_AGENT', 'CREATE_TEMPLATE', 'VIEW_AGENT_DIRECTORY')));

ALTER TABLE capability_grant_history DROP CONSTRAINT chk_capability_grant_history_capability;
ALTER TABLE capability_grant_history ADD CONSTRAINT chk_capability_grant_history_capability CHECK ((capability IN (
    'CREATE_SPACE', 'CREATE_LIBRARY', 'CREATE_CONNECTOR_LIBRARY', 'CREATE_INTERNAL_GROUP',
    'CREATE_PROMPT_LIBRARY', 'CREATE_AGENT', 'CREATE_TEMPLATE', 'VIEW_AGENT_DIRECTORY')));
