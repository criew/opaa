SET LOCAL lock_timeout = '5s';

-- The capabilities CREATE_AGENT and CREATE_TEMPLATE join the closed list. Only
-- values join, so the running release keeps writing what it wrote before; both values stand here
-- and in the document template's migration, so either order leaves both. Neither is delivered:
-- no organization gets a grant, the system administration hands them out.
ALTER TABLE capability_grants DROP CONSTRAINT chk_capability_grants_capability;

ALTER TABLE capability_grants ADD CONSTRAINT chk_capability_grants_capability CHECK ((capability IN ('CREATE_SPACE', 'CREATE_LIBRARY', 'CREATE_CONNECTOR_LIBRARY', 'CREATE_INTERNAL_GROUP', 'CREATE_PROMPT_LIBRARY', 'CREATE_AGENT', 'CREATE_TEMPLATE')));

ALTER TABLE capability_grant_history DROP CONSTRAINT chk_capability_grant_history_capability;

ALTER TABLE capability_grant_history ADD CONSTRAINT chk_capability_grant_history_capability CHECK ((capability IN ('CREATE_SPACE', 'CREATE_LIBRARY', 'CREATE_CONNECTOR_LIBRARY', 'CREATE_INTERNAL_GROUP', 'CREATE_PROMPT_LIBRARY', 'CREATE_AGENT', 'CREATE_TEMPLATE')));
