SET LOCAL lock_timeout = '5s';

-- What the person sees of the agent run that gave an answer (ADR-0070, Entscheidung 4, Bestand a):
-- run id, end state and the steps with their sub-questions, as JSON. Null on questions and on
-- every answer no agent gave. Lives and is deleted with the chat.
ALTER TABLE chat_messages ADD COLUMN agent_run json;
