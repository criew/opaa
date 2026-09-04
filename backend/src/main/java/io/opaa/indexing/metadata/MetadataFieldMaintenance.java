package io.opaa.indexing.metadata;

/**
 * The Pflege-Anker of one core field in one library (#1069, metadata-schema.md "Der Pflege-Anker"):
 * how many indexed documents carry no row for the field at all - "leer", the open rest - beside the
 * two states that are done, {@code SET} and {@code NOT_DETERMINABLE}. Only {@link
 * #documentsWithoutValue()} is the anchor's number, which is what lets it reach zero.
 *
 * @param totalDocuments the library's indexed documents, the base of {@link #missingShare()}
 */
public record MetadataFieldMaintenance(
    CoreMetadataField field,
    long totalDocuments,
    long filledDocuments,
    long notDeterminableDocuments) {

  /** Documents with neither a value nor a "kein Wert ermittelbar" mark. */
  public long documentsWithoutValue() {
    return Math.max(0, totalDocuments - filledDocuments - notDeterminableDocuments);
  }

  /** {@link #documentsWithoutValue()} / {@code totalDocuments} in 0..1; 0 for an empty library. */
  public double missingShare() {
    return totalDocuments == 0 ? 0d : (double) documentsWithoutValue() / totalDocuments;
  }
}
