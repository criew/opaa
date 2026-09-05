package io.opaa.indexing.metadata;

/**
 * What a confirmed value mapping did (#1071, metadata-schema.md "Kontrolliertes Vokabular statt
 * Freitext"): how many documents were rewritten onto the target value, how many were emptied
 * (mapped to "leer") and the correlation reference every audit event of the call carries, so the
 * whole mapping reads back from the audit log as one operation.
 */
public record LibraryFieldValueRemapResult(
    long remappedDocuments, long clearedDocuments, String correlationRef) {}
