SET LOCAL lock_timeout = '5s';

-- A hold of the deletion of a locked account's chats now names a purpose from a closed list, a
-- reason and the moment it ends by itself. NULL in all three is a hold placed before this release;
-- the nightly run gives it its end (now plus the maximum duration), so it no longer runs unbounded.
ALTER TABLE chat_cleanup_holds ADD COLUMN purpose varchar(40);
ALTER TABLE chat_cleanup_holds ADD COLUMN reason varchar(1000);
ALTER TABLE chat_cleanup_holds ADD COLUMN held_until timestamp with time zone;

-- A request to hold or to extend a hold, awaiting a second, different system administrator. It
-- holds nothing by itself; at most one per account. Deleting either account drops it.
CREATE TABLE chat_cleanup_hold_requests (
    user_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    purpose varchar(40) NOT NULL,
    reason varchar(1000) NOT NULL,
    requested_by_user_id uuid NOT NULL,
    requested_at timestamp with time zone NOT NULL,
    CONSTRAINT chat_cleanup_hold_requests_pkey PRIMARY KEY (user_id),
    CONSTRAINT chk_chat_cleanup_hold_requests_purpose CHECK (
        (purpose IN ('AUTHORITY_OR_COURT_ORDER', 'SECURITY_INCIDENT_EVIDENCE', 'DATA_SUBJECT_REQUEST'))),
    CONSTRAINT fk_chat_cleanup_hold_requests_user_organization FOREIGN KEY (user_id, organization_id)
        REFERENCES users(id, organization_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_cleanup_hold_requests_requested_by FOREIGN KEY (requested_by_user_id, organization_id)
        REFERENCES users(id, organization_id) ON DELETE CASCADE
);

CREATE INDEX idx_chat_cleanup_hold_requests_requested_by
    ON chat_cleanup_hold_requests (requested_by_user_id, organization_id);
