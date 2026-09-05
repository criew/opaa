package io.opaa.indexing.metadata;

/**
 * The Pflege-Anker of one field in one library (#1069, metadata-schema.md "Der Pflege-Anker"): how
 * many indexed documents carry no row for the field at all - "leer", the open rest - beside the two
 * states that are done, {@code SET} and {@code NOT_DETERMINABLE}. Core fields and library fields
 * (#1071) appear here alike; a library field's {@code fieldKey} carries its {@code lib:} namespace,
 * so the key is the same one every other operation on that field uses.
 *
 * <p>The counts themselves live in {@link MetadataFieldFill} - the one definition the
 * Zustandsübersicht shares (#1305).
 */
public record MetadataFieldMaintenance(String fieldKey, String label, MetadataFieldFill fill) {

  public long totalDocuments() {
    return fill.totalDocuments();
  }

  public long filledDocuments() {
    return fill.filledDocuments();
  }

  public long notDeterminableDocuments() {
    return fill.notDeterminableDocuments();
  }

  /** Documents with neither a value nor a "kein Wert ermittelbar" mark. */
  public long documentsWithoutValue() {
    return fill.documentsWithoutValue();
  }

  /** {@link #documentsWithoutValue()} / {@code totalDocuments} in 0..1; 0 for an empty library. */
  public double missingShare() {
    return fill.missingShare();
  }
}
