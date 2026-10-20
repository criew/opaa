package io.opaa.metadata;

/**
 * {@link EmbeddingRateMeasurements} of one process - for an estimator built without a database, as
 * in a unit test.
 */
final class ProcessLocalEmbeddingRateMeasurements implements EmbeddingRateMeasurements {

  private Totals totals = Totals.NONE;

  @Override
  public synchronized void add(long chunks, long nanos) {
    totals = totals.plus(chunks, nanos);
  }

  @Override
  public synchronized Totals totals() {
    return totals;
  }
}
