package io.opaa.indexing.metadata;

import io.opaa.api.types.LibraryMetadataSchemaChangeKind;

/**
 * One running schema change of a library as a reader sees it (metadata-schema.md, "Nachlauf im
 * Betrieb"): what is being retired, what it is mapped onto, how much is done and how much is left.
 *
 * @param valueCode the retired list value; {@code null} for a field deletion
 * @param targetCode the value the mapping writes onto; {@code null} means "leer"
 * @param remainingDocuments documents still carrying the retired value respectively field - the
 *     figure that reaches zero exactly when the list entry or the field is removed
 */
public record LibraryMetadataSchemaChangeView(
    LibraryMetadataSchemaChangeKind kind,
    String fieldKey,
    String valueCode,
    String targetCode,
    long processedDocuments,
    long remainingDocuments,
    String correlationRef) {}
