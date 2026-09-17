package io.opaa.indexing.metadata;

/**
 * What the confirming call of a value mapping did (metadata-schema.md "Kontrolliertes Vokabular
 * statt Freitext"): how many documents this Charge rewrote onto the target value, how many it
 * emptied (mapped to "leer"), how many still carry the retired value and the correlation reference
 * every audit event of the whole mapping carries, so it reads back from the audit log as one
 * operation across all of its Chargen.
 *
 * @param complete whether the list entry is gone - false means the run continues and {@code
 *     remainingDocuments} is what is left of it
 */
public record LibraryFieldValueRemapResult(
    long remappedDocuments,
    long clearedDocuments,
    String correlationRef,
    long remainingDocuments,
    boolean complete) {}
