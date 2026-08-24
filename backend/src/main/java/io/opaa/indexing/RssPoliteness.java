package io.opaa.indexing;

/**
 * The politeness delay {@link RssFeedIndexingExecutor} and {@link AttachmentIndexer} both apply
 * before a detail-page or attachment request - OPAA does not operate the servers an RSS feed points
 * at. Package-private - an implementation detail of the executor, not a new public API.
 */
final class RssPoliteness {

  private RssPoliteness() {}

  /**
   * Sleeps for {@code requestDelayMs} (a no-op for a non-positive value), restoring the interrupt
   * flag and returning immediately if interrupted while waiting.
   */
  static void delayBeforeRequest(long requestDelayMs) {
    if (requestDelayMs <= 0) {
      return;
    }
    try {
      Thread.sleep(requestDelayMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
