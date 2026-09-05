package io.opaa.indexing.metadata;

import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.MetadataOrigin;

/**
 * One library field value that belongs into a Beleg (metadata-schema.md Wirkstelle 3):
 * self-describing, so the Beleg renders it exactly like a core field. Only a field with a citation
 * position produces one, at most two per library, and never for an empty field - "kein Projekt —
 * ohne Angabe" is a gap that gets read with every answer and orders nothing.
 */
public record CitationFieldValue(
    String fieldKey,
    String label,
    String value,
    String displayValue,
    MetadataOrigin origin,
    DatePrecision datePrecision) {}
