SET LOCAL lock_timeout = '5s';

-- The optional live upstream of the transcription role (ADR-0070): dialect, WebSocket address,
-- model, an own key encrypted with the settings key (null sends the key of the batch endpoint) and
-- how many live sessions the installation holds at once (null for the factory default 1). All
-- null means dictation by recording only.
ALTER TABLE transcription_model ADD COLUMN realtime_protocol character varying(16);
ALTER TABLE transcription_model ADD COLUMN realtime_url character varying(500);
ALTER TABLE transcription_model ADD COLUMN realtime_model character varying(200);
ALTER TABLE transcription_model ADD COLUMN realtime_api_key_ciphertext text;
ALTER TABLE transcription_model ADD COLUMN realtime_max_sessions integer;
