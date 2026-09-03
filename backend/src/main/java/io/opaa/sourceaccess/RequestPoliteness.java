package io.opaa.sourceaccess;

/**
 * The politeness delay every connector that scrapes a remote source it does not operate applies
 * before a request - OPAA is a guest on the servers an RSS feed, a Confluence instance, or an
 * attachment link points at. Formerly RSS-only ({@code RssPoliteness}); moved here (#1182) so
 * {@code io.opaa.indexing.source.attachment.AttachmentIndexer} can use it without depending on
 * RSS's own package.
 */
public final class RequestPoliteness {

  private RequestPoliteness() {}

  /**
   * Sleeps for {@code requestDelayMs} (a no-op for a non-positive value), restoring the interrupt
   * flag and returning immediately if interrupted while waiting.
   */
  public static void delayBeforeRequest(long requestDelayMs) {
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
