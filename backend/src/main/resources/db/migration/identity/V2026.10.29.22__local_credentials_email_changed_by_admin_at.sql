SET LOCAL lock_timeout = '5s';

-- When the system administration last changed the address of a local account: an auditor deciding
-- a request for it sees whether the address is the person's own or was set shortly before.
ALTER TABLE local_credentials ADD COLUMN email_changed_by_admin_at timestamp with time zone;
