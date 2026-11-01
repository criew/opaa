SET LOCAL lock_timeout = '5s';

-- Documents attached to a chat question: uploaded into a space, owned by their uploader, bound to a
-- chat with the first question that names them and deleted with that chat. One never bound is
-- deleted after a day by ChatAttachmentCleanup.
CREATE TABLE chat_attachments (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    space_id uuid NOT NULL,
    author_id uuid NOT NULL,
    chat_id uuid,
    message_id uuid,
    file_name character varying(255) NOT NULL,
    content_type character varying(255),
    size_bytes bigint NOT NULL,
    original_locator character varying(1024),
    status character varying(20) NOT NULL,
    failure_reason character varying(500),
    text_chars integer,
    created_at timestamp with time zone NOT NULL,
    processed_at timestamp with time zone,
    CONSTRAINT chat_attachments_pkey PRIMARY KEY (id),
    CONSTRAINT chk_chat_attachments_status CHECK (status IN ('PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT chk_chat_attachments_message_needs_chat CHECK (message_id IS NULL OR chat_id IS NOT NULL),
    CONSTRAINT fk_chat_attachments_space_organization FOREIGN KEY (space_id, organization_id)
        REFERENCES spaces(id, organization_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_attachments_author_organization FOREIGN KEY (author_id, organization_id)
        REFERENCES users(id, organization_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_attachments_chat_organization FOREIGN KEY (chat_id, organization_id)
        REFERENCES chats(id, organization_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_attachments_message FOREIGN KEY (message_id)
        REFERENCES chat_messages(id) ON DELETE SET NULL
);

CREATE INDEX idx_chat_attachments_author ON chat_attachments (author_id, created_at);
CREATE INDEX idx_chat_attachments_chat ON chat_attachments (chat_id);
CREATE INDEX idx_chat_attachments_message ON chat_attachments (message_id);
CREATE INDEX idx_chat_attachments_unbound ON chat_attachments (created_at) WHERE chat_id IS NULL;

-- The text of an attachment as the format pipeline chunked it; the chunk metadata keeps Fundort and
-- anchor for the citation.
CREATE TABLE chat_attachment_chunks (
    attachment_id uuid NOT NULL,
    chunk_index integer NOT NULL,
    content text NOT NULL,
    metadata jsonb,
    CONSTRAINT chat_attachment_chunks_pkey PRIMARY KEY (attachment_id, chunk_index),
    CONSTRAINT fk_chat_attachment_chunks_attachment FOREIGN KEY (attachment_id)
        REFERENCES chat_attachments(id) ON DELETE CASCADE
);
