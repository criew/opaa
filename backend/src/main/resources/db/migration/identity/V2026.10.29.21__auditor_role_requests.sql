SET LOCAL lock_timeout = '5s';

-- The requests for the AUDITOR role (docs/features/access-control.md, Trennung von
-- Systemverwaltung und Revision): a system administrator or a provider's roles claim asks, an
-- auditor confirms or declines. One request per account. A decided request of the administration
-- is deleted, its trace is the audit log; a declined request of a provider stays as declined_at, so
-- the claim the next sign-in carries again does not reopen it, and ends once the claim no longer
-- names the role.
CREATE TABLE auditor_role_requests (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    user_id uuid NOT NULL,
    requested_by_user_id uuid,
    requested_by_provider_id uuid,
    requested_at timestamp with time zone NOT NULL,
    declined_at timestamp with time zone,
    CONSTRAINT auditor_role_requests_pkey PRIMARY KEY (id),
    CONSTRAINT ux_auditor_role_requests_user UNIQUE (user_id),
    CONSTRAINT ck_auditor_role_requests_one_source
        CHECK ((requested_by_user_id IS NULL) <> (requested_by_provider_id IS NULL)),
    CONSTRAINT ck_auditor_role_requests_declined_by_provider
        CHECK (declined_at IS NULL OR requested_by_provider_id IS NOT NULL),
    CONSTRAINT fk_auditor_role_requests_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
    CONSTRAINT fk_auditor_role_requests_user_organization FOREIGN KEY (user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE CASCADE,
    CONSTRAINT fk_auditor_role_requests_requested_by_organization FOREIGN KEY (requested_by_user_id, organization_id) REFERENCES users(id, organization_id) ON DELETE CASCADE,
    CONSTRAINT fk_auditor_role_requests_provider FOREIGN KEY (requested_by_provider_id) REFERENCES oidc_providers(id) ON DELETE CASCADE
);

CREATE INDEX idx_auditor_role_requests_organization ON auditor_role_requests USING btree (organization_id);
CREATE INDEX idx_auditor_role_requests_requested_by ON auditor_role_requests USING btree (requested_by_user_id);
CREATE INDEX idx_auditor_role_requests_provider ON auditor_role_requests USING btree (requested_by_provider_id);

-- When the system administration last changed the address of a local account: an auditor deciding
-- a request for it sees whether the address is the person's own or was set shortly before.
ALTER TABLE local_credentials ADD COLUMN email_changed_by_admin_at timestamp with time zone;
