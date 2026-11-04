SET LOCAL lock_timeout = '5s';

-- The installation's vocabulary list for speech input: terms and abbreviations sent as prompt with
-- every transcription, ahead of the person's own list. null means no list.
ALTER TABLE transcription_model ADD COLUMN vocabulary character varying(500);
