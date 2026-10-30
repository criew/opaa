SET LOCAL lock_timeout = '5s';

-- The encryption purposes (io.opaa.security.EncryptionPurpose) on their way to refusing their
-- unbound forms - enc:v1 and, for credentials, cleartext (ADR-0064, Entscheidung 6): measured_since
-- is the first re-encryption run in a row that found none, retired_at the run that confirmed it.
-- An instance started with OPAA_ENCRYPTION_KEYED_FORMAT=false clears the table.
CREATE TABLE cipher_legacy_retirements (
    purpose        varchar(32) PRIMARY KEY,
    measured_since timestamptz NOT NULL,
    retired_at     timestamptz
);
