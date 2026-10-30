SET LOCAL lock_timeout = '5s';

-- The encryption purposes (io.opaa.security.EncryptionPurpose) whose unbound forms - enc:v1 and,
-- for credentials, cleartext - are refused (ADR-0064, Entscheidung 6). Written once by the
-- re-encryption run when no such value is left; never cleared by the application.
CREATE TABLE cipher_legacy_retirements (
    purpose    varchar(32) PRIMARY KEY,
    retired_at timestamptz NOT NULL
);
