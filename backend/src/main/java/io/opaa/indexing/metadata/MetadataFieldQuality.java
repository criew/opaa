package io.opaa.indexing.metadata;

/**
 * Where the values of one field over one library came from (metadata-schema.md, "Messung und
 * Abnahme", Punkt 3). The four counts and the empty rest are what distinguishes a weak filter from
 * a weak extraction - a retrieval result alone cannot.
 */
public record MetadataFieldQuality(
    String fieldKey,
    String label,
    long totalDocuments,
    long deterministicDocuments,
    long derivedDocuments,
    long manualDocuments,
    long notDeterminableDocuments) {

  /** Documents carrying neither a value nor a "kein Wert ermittelbar" mark. */
  public long emptyDocuments() {
    return Math.max(
        0,
        totalDocuments
            - deterministicDocuments
            - derivedDocuments
            - manualDocuments
            - notDeterminableDocuments);
  }

  public double derivedShare() {
    return share(derivedDocuments);
  }

  public double emptyShare() {
    return share(emptyDocuments());
  }

  private double share(long count) {
    return totalDocuments == 0 ? 0d : (double) count / totalDocuments;
  }
}
