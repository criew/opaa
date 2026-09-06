package io.opaa.indexing.metadata;

import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.MetadataOrigin;

/**
 * One non-core field value that belongs into a Beleg (metadata-schema.md Wirkstelle 3):
 * self-describing, so the Beleg renders it exactly like a core field. A library field produces one
 * only with a citation position, at most two per library, and never for an empty field - "kein
 * Projekt — ohne Angabe" is a gap that gets read with every answer and orders nothing.
 *
 * @param detailOnly whether the value belongs into the Beleg detail view but not into the one-line
 *     Fundstellenzeile
 */
public record CitationFieldValue(
    String fieldKey,
    String label,
    String value,
    String displayValue,
    MetadataOrigin origin,
    DatePrecision datePrecision,
    boolean detailOnly) {

  /** An entry the Fundstellenzeile shows - every library field and most format fields. */
  public CitationFieldValue(
      String fieldKey,
      String label,
      String value,
      String displayValue,
      MetadataOrigin origin,
      DatePrecision datePrecision) {
    this(fieldKey, label, value, displayValue, origin, datePrecision, false);
  }
}
