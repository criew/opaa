SET LOCAL lock_timeout = '5s';

-- Backs the sum of size_bytes per organization that the organization quota of chat attachments
-- checks on every upload while it is set.
CREATE INDEX idx_chat_attachments_organization ON chat_attachments (organization_id) INCLUDE (size_bytes);
