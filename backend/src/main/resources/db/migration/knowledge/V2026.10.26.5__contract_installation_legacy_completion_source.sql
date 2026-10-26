SET LOCAL lock_timeout = '5s';

-- CompletionSource.LEGACY_START_VALUES left the enum with the start values of the transition
-- (ADR-0060, Entscheidung 7): an installation completed that way counts as one found in use.
UPDATE installation_state
SET completion_source = 'EXISTING'
WHERE completion_source = 'LEGACY_START_VALUES';
