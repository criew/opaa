package io.opaa.indexing.source.rss;

import io.opaa.indexing.source.attachment.AttachmentIndexer;

/**
 * The politeness delay {@link RssFeedIndexingExecutor} and {@link AttachmentIndexer} both apply
 * before a detail-page or attachment request - OPAA does not operate the servers an RSS feed points
 * at.
 *
 * <p>Public - consumed from {@link AttachmentIndexer} in the sibling {@code source.attachment}
 * package (#1113); still not part of any cross-module API surface.
 */
public final class RssPoliteness {

  private RssPoliteness() {}

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
