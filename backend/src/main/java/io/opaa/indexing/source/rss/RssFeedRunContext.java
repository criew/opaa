package io.opaa.indexing.source.rss;

import io.opaa.indexing.attachment.AttachmentAccess;
import io.opaa.indexing.job.IndexingRunEventRecorder;
import io.opaa.indexing.spi.IndexingRunProgress;
import io.opaa.indexing.spi.RequestBudget;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.sourceaccess.RateLimitListener;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Everything a single RSS indexing run shares across every entry and attachment it processes, in
 * one object instead of a 9-10-parameter signature threaded through {@link
 * RssFeedIndexingExecutor}.
 *
 * <p>{@link #authHeaderFor} withholds {@code Authorization} for any target outside the feed's own
 * origin - an entry's {@code <link>} is content the feed operator controls; {@link #httpClient}
 * checks every certificate; {@link #authHeader} answers the header valid now, {@code null} for
 * none, and is asked before every request. {@link #requestBudget} counts and bounds every request
 * of the run - feed, detail pages, attachments. {@link #anyEntryDeferred} is the only mutable
 * field: set by the executor or {@code AttachmentIndexer} on any deferral, never reset, and read
 * once before deciding whether the conditional-GET state may be saved. {@link #extraction} are the
 * library's settings for every detail page; {@link #reprocessing} lets no entry be skipped as
 * unchanged, since the stored ones were extracted under other settings.
 */
public record RssFeedRunContext(
    HttpClient httpClient,
    KnowledgeLibrary targetLibrary,
    Supplier<String> authHeader,
    String feedUrl,
    IndexingRunProgress progress,
    IndexingRunEventRecorder events,
    AtomicBoolean anyEntryDeferred,
    RequestBudget requestBudget,
    DetailPageExtractor.Extraction extraction,
    boolean reprocessing)
    implements AttachmentAccess {

  /** A run under the factory defaults whose stored entries need no reprocessing. */
  public RssFeedRunContext(
      HttpClient httpClient,
      KnowledgeLibrary targetLibrary,
      Supplier<String> authHeader,
      String feedUrl,
      IndexingRunProgress progress,
      IndexingRunEventRecorder events,
      AtomicBoolean anyEntryDeferred,
      RequestBudget requestBudget) {
    this(
        httpClient,
        targetLibrary,
        authHeader,
        feedUrl,
        progress,
        events,
        anyEntryDeferred,
        requestBudget,
        DetailPageExtractor.Extraction.of(RssFeedSettings.read(null)),
        false);
  }

  /** {@link AttachmentAccess#markDeferred()} - delegates to {@link #anyEntryDeferred}. */
  @Override
  public void markDeferred() {
    anyEntryDeferred.set(true);
  }

  @Override
  public RateLimitListener rateLimitListener() {
    return requestBudget;
  }

  /**
   * Restricts {@link #authHeader} to a request whose target shares the feed's own origin. Neither
   * {@code DetailPageExtractor} nor {@code BoundedDownloader#downloadBounded}'s own foreign-host
   * checks protect against the starting address of a request: both only compare a redirect hop
   * against the previous one, never against the feed itself. An unparseable {@code targetUrl} is
   * treated as foreign.
   */
  public String authHeaderFor(String targetUrl) {
    String header = authHeader == null ? null : authHeader.get();
    if (header == null) {
      return null;
    }
    return isSameOriginAsFeed(targetUrl) ? header : null;
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
