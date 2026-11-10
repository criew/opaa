SET LOCAL lock_timeout = '5s';

-- Two capabilities join the list a grant may name: CREATE_TEMPLATE (document templates) and
-- CREATE_AGENT (agents), which a parallel change adds as well, so either order of the two scripts
-- ends with both. Only values join, so the running release keeps writing what it wrote before.
-- Neither is delivered to anybody: no grant row is written.
ALTER TABLE capability_grants DROP CONSTRAINT chk_capability_grants_capability;

ALTER TABLE capability_grants ADD CONSTRAINT chk_capability_grants_capability CHECK ((capability IN ('CREATE_SPACE', 'CREATE_LIBRARY', 'CREATE_CONNECTOR_LIBRARY', 'CREATE_INTERNAL_GROUP', 'CREATE_PROMPT_LIBRARY', 'CREATE_AGENT', 'CREATE_TEMPLATE')));

ALTER TABLE capability_grant_history DROP CONSTRAINT chk_capability_grant_history_capability;

ALTER TABLE capability_grant_history ADD CONSTRAINT chk_capability_grant_history_capability CHECK ((capability IN ('CREATE_SPACE', 'CREATE_LIBRARY', 'CREATE_CONNECTOR_LIBRARY', 'CREATE_INTERNAL_GROUP', 'CREATE_PROMPT_LIBRARY', 'CREATE_AGENT', 'CREATE_TEMPLATE')));
