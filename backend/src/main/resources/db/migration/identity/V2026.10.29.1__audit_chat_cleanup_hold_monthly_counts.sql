SET LOCAL lock_timeout = '5s';

-- How often the administration held the deletion of the chats of a locked account, per calendar
-- month, for the report to the staff council: no person, no account, nothing finer than a month.
CREATE TABLE audit_chat_cleanup_hold_monthly_counts (
    organization_id uuid NOT NULL,
    hold_month date NOT NULL,
    hold_count integer NOT NULL,
    CONSTRAINT audit_chat_cleanup_hold_monthly_counts_pkey PRIMARY KEY (organization_id, hold_month),
    CONSTRAINT chk_audit_chat_cleanup_hold_monthly_counts_count CHECK ((hold_count > 0)),
    CONSTRAINT chk_audit_chat_cleanup_hold_monthly_counts_month CHECK ((hold_month = (date_trunc('month'::text, (hold_month)::timestamp with time zone))::date)),
    CONSTRAINT fk_audit_chat_cleanup_hold_monthly_counts_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE
);
