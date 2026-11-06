package io.opaa.indexing.job;

/**
 * The pages one indexing run may send to text recognition (OCR) and the scans it left for the next
 * run. Held by the run's protocol ({@link IndexingEventSink#textRecognitionBudget()}), so every
 * document and attachment of the run draws from it; the run frame ends a run that deferred a scan
 * as incomplete. Thread-safe: a run may ingest in parallel.
 */
public final class TextRecognitionRunBudget {

  private int pagesSpent;
  private int deferred;

  /**
   * Reserves {@code pages} under {@code limit}. The first scan of a run always fits, so a scan
   * longer than the budget is still read once per run rather than never.
   *
   * @return whether the pages were reserved; {@code false} counts the scan as deferred
   */
  public synchronized boolean tryReserve(int pages, int limit) {
    if (pagesSpent > 0 && pagesSpent + pages > limit) {
      deferred++;
      return false;
    }
    pagesSpent += pages;
    return true;
  }

  /** Returns pages reserved for a scan that was not sent after all. */
  public synchronized void release(int pages) {
    pagesSpent = Math.max(0, pagesSpent - pages);
  }

  public synchronized int pagesSpent() {
    return pagesSpent;
  }

  /** Scans this run left for the next one because the budget was spent. */
  public synchronized int deferred() {
    return deferred;
  }
}
