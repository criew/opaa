SET LOCAL lock_timeout = '5s';

-- The Belegstand of an answer as assessed when it was generated (AnswerEvidence as JSON); null on
-- questions and on answers from before the column existed.
ALTER TABLE chat_messages ADD COLUMN answer_evidence json;
