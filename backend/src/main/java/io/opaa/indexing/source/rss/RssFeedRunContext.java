package io.opaa.indexing.source.rss;

import io.opaa.indexing.IndexingRunEventRecorder;
import io.opaa.indexing.IndexingRunProgress;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything a single RSS indexing run shares across every entry and attachment it processes -
 * collapses the 9-10-parameter methods {@link RssFeedIndexingExecutor} used to thread this same
 * state through individually into one object.
 *
 * <p><b>{@link #httpClientFor}/{@link #authHeaderFor}.</b> {@code sourceInsecureSsl}/{@code
 * Authorization} are withheld for any target outside the feed's own origin - an entry's {@code
 * <link>} or an attachment URL is content the feed operator controls, not a target the library
 * owner vouches for. {@code secureClient} always validates certificates normally; {@code
 * insecureClient} relaxes validation only when the library asks for it and is used exclusively for
 * same-origin requests.
 *
 * <p><b>{@link #anyEntryDeferred}</b> is this record's only mutable field - written by both {@link
 * RssFeedIndexingExecutor} and {@code AttachmentIndexer} the moment either defers or fails
 * something, never reset for the lifetime of a run, and read exactly once, in {@link
 * RssFeedIndexingExecutor#execute}, right before deciding whether the feed's conditional-GET state
 * may be saved.
 */
public record RssFeedRunContext(
    HttpClient secureClient,
    HttpClient insecureClient,
    KnowledgeLibrary targetLibrary,
    String authHeader,
    String feedUrl,
    IndexingRunProgress progress,
    IndexingRunEventRecorder events,
    AtomicBoolean anyEntryDeferred) {

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
