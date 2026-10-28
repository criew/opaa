SET LOCAL lock_timeout = '5s';

-- A library field no longer carries a citation position (ADR-0024, Entscheidung 7): every set
-- value travels with a Beleg to external AI tools, in sort_order. citation_enabled only ever
-- mirrored citation_position, so both columns go, with their check and their unique index.
DROP INDEX uk_library_metadata_fields_citation_position;
ALTER TABLE library_metadata_fields
    DROP CONSTRAINT chk_library_metadata_fields_citation_position;
ALTER TABLE library_metadata_fields DROP COLUMN citation_position;
ALTER TABLE library_metadata_fields DROP COLUMN citation_enabled;
