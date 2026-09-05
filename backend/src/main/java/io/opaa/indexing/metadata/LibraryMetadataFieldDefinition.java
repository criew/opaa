package io.opaa.indexing.metadata;

import java.util.List;

/**
 * One library field with its configured value list (#1071) - the shape every read of the schema
 * returns. The list is schema, not an aggregate over documents: it exists whether or not a document
 * carries a value, and it is visible to everyone who may use the library (metadata-schema.md,
 * Rechte-Invariante, Ausnahme für die konfigurierte Werteliste). Empty for a DATE or PATTERN field.
 */
public record LibraryMetadataFieldDefinition(
    LibraryMetadataField field, List<LibraryMetadataFieldValue> values) {}
