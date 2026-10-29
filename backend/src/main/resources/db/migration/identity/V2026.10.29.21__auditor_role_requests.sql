SET LOCAL lock_timeout = '5s';

-- The open requests for the AUDITOR role (docs/features/access-control.md, Trennung von
-- Systemverwaltung und Revision): a system administrator asks, an auditor confirms or declines.
-- One open request per account; a decided request is deleted, its trace is the audit log.
CREATE TABLE auditor_role_requests (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    user_id uuid NOT NULL,
    requested_by_user_id uuid NOT NULL,
    requested_at timestamp with time zone NOT NULL,
    CONSTRAINT auditor_role_requests_pkey PRIMARY KEY (id),
    CONSTRAINT ux_auditor_role_requests_user UNIQUE (user_id),
    CONSTRAINT fk_auditor_role_requests_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
    CONSTRAINT fk_auditor_role_requests_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_auditor_role_requests_requested_by FOREIGN KEY (requested_by_user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_auditor_role_requests_organization ON auditor_role_requests USING btree (organization_id);
CREATE INDEX idx_auditor_role_requests_requested_by ON auditor_role_requests USING btree (requested_by_user_id);
