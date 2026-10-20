package io.opaa.metadata;

/**
 * The embedding calls measured by every instance of an installation, summed (ADR-0053, Entscheidung
 * 12): the cost preview of a schema change names the same duration whichever instance computes it.
 */
public interface EmbeddingRateMeasurements {

  /** Adds measured calls with {@code chunks} chunks in {@code nanos} wall time. */
  void add(long chunks, long nanos);

  /** Everything measured so far. */
  Totals totals();

  /** Measured chunks and the wall time they took. */
  record Totals(long chunks, long nanos) {

    public static final Totals NONE = new Totals(0, 0);

    Totals plus(long moreChunks, long moreNanos) {
      return new Totals(chunks + moreChunks, nanos + moreNanos);
    }
  }
}
