package io.opaa.indexing;

import io.opaa.library.KnowledgeLibrary;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything a single RSS indexing run shares across every entry and attachment it processes -
 * collapses the 9-10-parameter methods {@link RssFeedIndexingExecutor} used to thread this same
 * state through individually (#876, Epic #826 finding B7) into one object. Package-private - an
 * implementation detail of the executor, not a new public API.
 *
 * <p><b>{@link #httpClientFor}/{@link #authHeaderFor}.</b> {@code sourceInsecureSsl}/{@code
 * Authorization} are withheld for any target outside the feed's own origin - an entry's {@code
 * <link>} or an attachment URL is content the feed operator controls, not a target the library
 * owner vouches for. {@code secureClient} always validates certificates normally; {@code
 * insecureClient} relaxes validation only when the library asks for it and is used exclusively for
 * same-origin requests.
 */
record RssFeedRunContext(
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
  HttpClient httpClientFor(String targetUrl) {
    return isSameOriginAsFeed(targetUrl) ? insecureClient : secureClient;
  }

  /**
   * Restricts {@link #authHeader} to a request whose target shares the feed's own origin. Neither
   * {@code DetailPageExtractor} nor {@code BoundedDownloader#downloadBounded}'s own foreign-host
   * checks protect against the starting address of a request: both only compare a redirect hop
   * against the previous one, never against the feed itself. An unparseable {@code targetUrl} is
   * treated as foreign.
   */
  String authHeaderFor(String targetUrl) {
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
