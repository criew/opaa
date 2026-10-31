SET LOCAL lock_timeout = '5s';

-- The steps of the holds of the deletion of a locked account's chats per calendar month and
-- purpose, for the report to the staff council: no person, no account, no reason, nothing finer
-- than a month. The counts outlive the holds and their requests.
CREATE TABLE audit_chat_cleanup_hold_purpose_counts (
    organization_id uuid NOT NULL,
    case_month date NOT NULL,
    purpose varchar(40) NOT NULL,
    transition varchar(10) NOT NULL,
    case_count integer NOT NULL,
    CONSTRAINT audit_chat_cleanup_hold_purpose_counts_pkey
        PRIMARY KEY (organization_id, case_month, purpose, transition),
    CONSTRAINT chk_audit_chat_cleanup_hold_purpose_counts_count CHECK ((case_count > 0)),
    CONSTRAINT chk_audit_chat_cleanup_hold_purpose_counts_month CHECK (
        (case_month = (date_trunc('month'::text, (case_month)::timestamp with time zone))::date)),
    CONSTRAINT chk_audit_chat_cleanup_hold_purpose_counts_purpose CHECK (
        (purpose IN ('AUTHORITY_OR_COURT_ORDER', 'SECURITY_INCIDENT_EVIDENCE', 'DATA_SUBJECT_REQUEST'))),
    CONSTRAINT chk_audit_chat_cleanup_hold_purpose_counts_transition CHECK (
        (transition IN ('REQUESTED', 'APPROVED', 'EXTENDED', 'REJECTED', 'EXPIRED'))),
    CONSTRAINT fk_audit_chat_cleanup_hold_purpose_counts_organization FOREIGN KEY (organization_id)
        REFERENCES organizations(id) ON DELETE CASCADE
);
