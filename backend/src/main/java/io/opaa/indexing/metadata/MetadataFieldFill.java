package io.opaa.indexing.metadata;

/**
 * How far one metadata field is filled over one bestand: the three counts and the two figures
 * derived from them. The single definition of {@code documentsWithoutValue} and {@code
 * missingShare} - the Pflege-Anker in a library's settings and the Zustandsübersicht of the
 * organization say the same thing about the same field and must not be able to drift apart. The
 * bestand it is measured over is the caller's scope: one library in the person's rights context, or
 * the organization's libraries in the administrative context (metadata-schema.md,
 * Rechte-Invariante).
 *
 * @param totalDocuments indexed documents of the scope - the base of both shares
 * @param filledDocuments documents carrying a {@code SET} value
 * @param notDeterminableDocuments documents a person marked as having no value to find - done, not
 *     open, which is what lets {@link #documentsWithoutValue()} reach zero
 */
public record MetadataFieldFill(
    long totalDocuments, long filledDocuments, long notDeterminableDocuments) {

  public static final MetadataFieldFill EMPTY = new MetadataFieldFill(0, 0, 0);

  /** Documents with neither a value nor a "kein Wert ermittelbar" mark - the anchor's number. */
  public long documentsWithoutValue() {
    return Math.max(0, totalDocuments - filledDocuments - notDeterminableDocuments);
  }

  /** {@link #documentsWithoutValue()} / {@code totalDocuments} in 0..1; 0 for an empty scope. */
  public double missingShare() {
    return totalDocuments == 0 ? 0d : (double) documentsWithoutValue() / totalDocuments;
  }

  /**
   * {@code filledDocuments} / {@code totalDocuments} in 0..1; 0 for an empty scope. The Füllgrad of
   * the Zustandsübersicht, which counts only real values - unlike the Füllstand of the filter entry
   * condition, where a "kein Wert ermittelbar" mark counts as answered.
   */
  public double filledShare() {
    return totalDocuments == 0 ? 0d : (double) filledDocuments / totalDocuments;
  }

  /** The same fill over a scope of {@code totalDocuments}, keeping both value counts. */
  public MetadataFieldFill withTotal(long total) {
    return new MetadataFieldFill(total, filledDocuments, notDeterminableDocuments);
  }
}
