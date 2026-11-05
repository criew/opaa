SET LOCAL lock_timeout = '5s';

-- When the personal reference of the account was removed (erasure on a request under Art. 17
-- GDPR); null for every other account. The row itself stays as a nameless shell, so the rights
-- history and the audit trail keep their references (ADR-0016).
ALTER TABLE users ADD COLUMN erased_at timestamp with time zone;
