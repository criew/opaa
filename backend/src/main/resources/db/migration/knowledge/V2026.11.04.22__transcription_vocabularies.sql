SET LOCAL lock_timeout = '5s';

-- A person's own vocabulary list for speech input, at most one per account. Visible only to the
-- person, never evaluated, sent as prompt after the installation's list; deleted with the account.
CREATE TABLE transcription_vocabularies (
    user_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    vocabulary character varying(500) NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT transcription_vocabularies_pkey PRIMARY KEY (user_id),
    CONSTRAINT fk_transcription_vocabularies_user_organization FOREIGN KEY (user_id, organization_id)
        REFERENCES users(id, organization_id) ON DELETE CASCADE
);
