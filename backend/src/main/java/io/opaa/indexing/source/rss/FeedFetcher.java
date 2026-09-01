package io.opaa.indexing.source.rss;

import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.IndexingRunProgress;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The RSS feed's own transport concerns, split out of {@link RssFeedIndexingExecutor}: the
 * conditional {@code GET} against the feed URL itself (ETag/{@code If-Modified-Since}, tracked per
 * library and feed URL in {@link RssFeedState} - see that class's own Javadoc for why it is keyed
 * by both), the bounded read of the feed body, and persisting the feed's own conditional-GET state
 * once a run has accounted for every entry it saw. Package-private - an implementation detail of
 * the executor, not a new public API.
 */
class FeedFetcher {

  private static final Logger log = LoggerFactory.getLogger(FeedFetcher.class);

  private final TargetAddressValidator targetAddressValidator;
  private final RssFeedStateRepository feedStateRepository;
  private final RssFeedParser feedParser;
  private final IndexingProperties.Rss properties;

  FeedFetcher(
      TargetAddressValidator targetAddressValidator,
      RssFeedStateRepository feedStateRepository,
      RssFeedParser feedParser,
      IndexingProperties.Rss properties) {
    this.targetAddressValidator = targetAddressValidator;
    this.feedStateRepository = feedStateRepository;
    this.feedParser = feedParser;
    this.properties = properties;
  }

  /** The feed response and parsed entries {@link #fetchAndParse} hands back on success. */
  record LoadedFeed(
      HttpResponse<InputStream> feedResponse, List<RssFeedEntry> entries, boolean truncated) {}

  /**
   * Fetches, reads and parses {@code feedUrl} in one step, returning {@link Optional#empty()} once
   * the run is already terminal (unchanged {@code 304}, transport error, unparseable/oversized
   * feed) - {@code progress} has already been failed/completed in that case, and the caller just
   * returns.
   */
  Optional<LoadedFeed> fetchAndParse(
      HttpClient httpClient,
      UUID libraryId,
      String feedUrl,
      String authHeader,
      IndexingRunProgress progress)
      throws IOException, InterruptedException {
    Optional<RssFeedState> feedState = findState(libraryId, feedUrl);
    HttpResponse<InputStream> feedResponse = fetchFeed(httpClient, feedUrl, feedState, authHeader);

    if (feedResponse.statusCode() == 304) {
      closeQuietly(feedResponse.body());
      log.info("RSS feed unchanged (304), ending run: {}", feedUrl);
      progress.setTotal(0);
      progress.complete();
      return Optional.empty();
    }
    if (feedResponse.statusCode() != 200) {
      closeQuietly(feedResponse.body());
      progress.fail(
          "Der RSS-Feed konnte nicht abgerufen werden: HTTP " + feedResponse.statusCode());
      return Optional.empty();
    }

    List<RssFeedEntry> entries;
    try (InputStream body = feedResponse.body()) {
      byte[] feedBytes = readFeedBody(body);
      entries = feedParser.parse(new ByteArrayInputStream(feedBytes));
    } catch (RssFeedParseException e) {
      // German, user-facing message straight from the parser.
      log.warn("RSS feed did not parse: {}", feedUrl, e);
      progress.fail(e.getMessage());
      return Optional.empty();
    } catch (FeedTooLargeException e) {
      progress.fail(
          "Der RSS-Feed überschreitet die zulässige Größe von "
              + properties.maxFeedSizeBytes()
              + " Byte.");
      return Optional.empty();
    }

    // Whether entries were deferred (truncated below, or skipped because the remote end
    // rejected/failed to hand over a detail page) decides whether the feed's ETag/Last-Modified
    // may be persisted at all - see the caller's own saveState call.
    boolean truncated = entries.size() > properties.maxEntries();
    if (truncated) {
      log.info(
          "RSS feed {} carries {} entries, processing only the first {} (opaa.indexing.rss.max-entries)",
          feedUrl,
          entries.size(),
          properties.maxEntries());
      entries = entries.subList(0, properties.maxEntries());
    }
    return Optional.of(new LoadedFeed(feedResponse, entries, truncated));
  }

  private static void closeQuietly(InputStream in) {
    try {
      in.close();
    } catch (IOException e) {
      log.debug("Failed to close response body", e);
    }
  }

  /**
   * Looks up the previous run's conditional-GET state for {@code (libraryId, feedUrl)} - keyed by
   * both, not {@code feedUrl} alone, since the same feed URL could in principle be configured on
   * two different libraries with independent state (see {@link RssFeedState}'s own Javadoc).
   */
  private Optional<RssFeedState> findState(UUID libraryId, String feedUrl) {
    return feedStateRepository.findByLibraryIdAndFeedUrl(libraryId, feedUrl);
  }

  /**
   * Fetches {@code feedUrl}, carrying {@code feedState}'s {@code ETag}/{@code Last-Modified} as a
   * conditional {@code GET} when present - a {@code 304} response then ends the caller's run early
   * without a body ever being read. Follows redirects that leave the feed's own origin ({@link
   * RedirectFollowingFetcher.RedirectPolicy#DROP_AUTHORIZATION_OFF_ORIGIN}), dropping {@code
   * Authorization} the moment a hop stops matching - a redirect chain a feed's own hosting provider
   * sets up (e.g. onto a CDN) must not leak the feed's credentials to it.
   */
  private HttpResponse<InputStream> fetchFeed(
      HttpClient httpClient, String feedUrl, Optional<RssFeedState> feedState, String authHeader)
      throws IOException, InterruptedException {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("User-Agent", properties.userAgent());
    if (authHeader != null) {
      headers.put("Authorization", authHeader);
    }
    feedState.ifPresent(
        state -> {
          if (state.getEtag() != null) {
            headers.put("If-None-Match", state.getEtag());
          }
          if (state.getLastModified() != null) {
            headers.put("If-Modified-Since", state.getLastModified());
          }
        });
    return RedirectFollowingFetcher.sendFollowingRedirects(
        httpClient,
        feedUrl,
        Duration.ofSeconds(60),
        headers,
        targetAddressValidator,
        RedirectFollowingFetcher.RedirectPolicy.DROP_AUTHORIZATION_OFF_ORIGIN);
  }

  /**
   * Reads at most {@link IndexingProperties.Rss#maxFeedSizeBytes()} from {@code body}, throwing
   * {@link FeedTooLargeException} the moment a further byte would exceed the limit - enforced while
   * streaming, not after the full response has already been downloaded.
   */
  private byte[] readFeedBody(InputStream body) throws IOException {
    byte[] probe =
        body.readNBytes(
            Math.toIntExact(Math.min(properties.maxFeedSizeBytes() + 1, Integer.MAX_VALUE)));
    if (probe.length > properties.maxFeedSizeBytes()) {
      throw new FeedTooLargeException();
    }
    return probe;
  }

  /**
   * Persists {@code feedResponse}'s {@code ETag}/{@code Last-Modified} for the next run's
   * conditional {@code GET} - a no-op when the response carries neither header. The caller only
   * invokes this once a run has accounted for every entry it saw (see {@link
   * RssFeedIndexingExecutor#execute}); saving state after a run that deferred or failed an entry
   * would let a future {@code 304} permanently hide it.
   */
  void saveState(UUID libraryId, String feedUrl, HttpResponse<InputStream> feedResponse) {
    String etag = feedResponse.headers().firstValue("ETag").orElse(null);
    String lastModified = feedResponse.headers().firstValue("Last-Modified").orElse(null);
    if (etag == null && lastModified == null) {
      return;
    }
    RssFeedState state =
        feedStateRepository
            .findByLibraryIdAndFeedUrl(libraryId, feedUrl)
            .orElseGet(() -> new RssFeedState(libraryId, feedUrl, null, null));
    state.setEtag(etag);
    state.setLastModified(lastModified);
    state.setUpdatedAt(Instant.now());
    feedStateRepository.save(state);
  }

  /** Thrown by {@link #readFeedBody} when the configured byte limit is exceeded while streaming. */
  static final class FeedTooLargeException extends RuntimeException {}
}
