package io.opaa.indexing.source.rss;

import io.opaa.indexing.IndexingRunEventRecorder;
import io.opaa.indexing.IndexingRunProgress;
import io.opaa.indexing.source.attachment.AttachmentAccess;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything a single RSS indexing run shares across every entry and attachment it processes, in
 * one object instead of a 9-10-parameter signature threaded through {@link
 * RssFeedIndexingExecutor}.
 *
 * <p>{@link #httpClientFor}/{@link #authHeaderFor} withhold {@code sourceInsecureSsl} and {@code
 * Authorization} for any target outside the feed's own origin - an entry's {@code <link>} is
 * content the feed operator controls. {@link #anyEntryDeferred} is the only mutable field: set by
 * the executor or {@code AttachmentIndexer} on any deferral, never reset, and read once before
 * deciding whether the conditional-GET state may be saved.
 */
public record RssFeedRunContext(
    HttpClient secureClient,
    HttpClient insecureClient,
    KnowledgeLibrary targetLibrary,
    String authHeader,
    String feedUrl,
    IndexingRunProgress progress,
    IndexingRunEventRecorder events,
    AtomicBoolean anyEntryDeferred)
    implements AttachmentAccess {

  /** {@link AttachmentAccess#markDeferred()} - delegates to {@link #anyEntryDeferred}. */
  @Override
  public void markDeferred() {
    anyEntryDeferred.set(true);
  }

  /**
   * Picks {@code insecureClient} for a request whose target shares the feed's own origin, {@code
   * secureClient} for anything else. An unparseable {@code targetUrl} is treated as foreign (the
   * secure client).
   */
  public HttpClient httpClientFor(String targetUrl) {
    return isSameOriginAsFeed(targetUrl) ? insecureClient : secureClient;
  }

  /**
   * Restricts {@link #authHeader} to a request whose target shares the feed's own origin. Neither
   * {@code DetailPageExtractor} nor {@code BoundedDownloader#downloadBounded}'s own foreign-host
   * checks protect against the starting address of a request: both only compare a redirect hop
   * against the previous one, never against the feed itself. An unparseable {@code targetUrl} is
   * treated as foreign.
   */
  public String authHeaderFor(String targetUrl) {
    if (authHeader == null) {
      return null;
    }
    return isSameOriginAsFeed(targetUrl) ? authHeader : null;
  }

  private boolean isSameOriginAsFeed(String targetUrl) {
    try {
      return RedirectFollowingFetcher.sameOrigin(URI.create(feedUrl), URI.create(targetUrl));
    } catch (IllegalArgumentException e) {
      // An unparseable target URL is never trusted as same-origin.
      return false;
    }
  }
}
