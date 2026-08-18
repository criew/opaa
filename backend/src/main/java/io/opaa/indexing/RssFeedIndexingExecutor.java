package io.opaa.indexing;

import io.opaa.api.dto.IndexingTriggerRequest;
import io.opaa.library.KnowledgeLibrary;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

/**
 * Executes indexing runs for {@link IndexingSourceType#RSS_FEED} (#467, ADR-0017): fetches an RSS
 * 2.0 feed, resolves every entry's detail page and hands the page's main text - not the whole page
 * - into the shared processing chain via {@link FileProcessingService#processRssEntry}.
 *
 * <p><b>Two-stage change detection (ADR-0017).</b> The feed itself is fetched with a conditional
 * {@code GET} (ETag/{@code If-Modified-Since}, tracked per feed URL in {@link RssFeedState}) - an
 * unchanged feed ends the run after a single {@code 304} response. Every entry is then checked
 * against its stored {@code pubDate} <em>before</em> its detail page is ever requested (mirroring
 * {@link UrlIndexingExecutor#isUnchanged}), so an unchanged entry costs nothing beyond the already-
 * downloaded feed. The SHA-256 checksum computed inside {@link
 * FileProcessingService#processRssEntry} is the final, content-based layer once a page has actually
 * been fetched.
 *
 * <p><b>No deletion by absence (ADR-0017, decision 5).</b> An entry that has scrolled out of the
 * feed's window is not touched here - see the ADR for why that would silently lose still-valid
 * older articles.
 *
 * <p><b>Hardening against a feed operator OPAA does not control</b> (#467, PR #474 review of the
 * parser this executor drives): entry- and byte-size limits, a link scheme check (only {@code
 * http}/{@code https} detail pages are ever fetched), a minimum delay between detail-page requests
 * and a truthful, configurable {@code User-Agent} - all from {@link IndexingProperties.Rss}. A
 * single rejected, oversized or unreachable entry never aborts the run; it is skipped, counted and
 * logged, and the run continues (ADR-0017's "Verhalten gegenüber fremden Zielen").
 */
public class RssFeedIndexingExecutor implements SourceIndexingExecutor {

  private static final Logger log = LoggerFactory.getLogger(RssFeedIndexingExecutor.class);

  private final RssFeedParser feedParser;
  private final FileProcessingService fileProcessingService;
  private final IndexingJobService indexingJobService;
  private final DocumentRepository documentRepository;
  private final RssFeedStateRepository feedStateRepository;
  private final IndexingProperties.Rss properties;

  public RssFeedIndexingExecutor(
      RssFeedParser feedParser,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      DocumentRepository documentRepository,
      RssFeedStateRepository feedStateRepository,
      IndexingProperties properties) {
    this.feedParser = feedParser;
    this.fileProcessingService = fileProcessingService;
    this.indexingJobService = indexingJobService;
    this.documentRepository = documentRepository;
    this.feedStateRepository = feedStateRepository;
    this.properties = properties.rss();
  }

  @Override
  public IndexingSourceType sourceType() {
    return IndexingSourceType.RSS_FEED;
  }

  @Override
  @Async("indexingTaskExecutor")
  public void execute(
      UUID jobId, IndexingTriggerRequest triggerRequest, KnowledgeLibrary targetLibrary) {
    var progress = new IndexingRunProgress(indexingJobService, jobId);
    String feedUrl = triggerRequest.getUrl() != null ? triggerRequest.getUrl().toString() : null;

    try {
      HttpClient httpClient = AutoindexCrawlerService.buildHttpClient(null, -1, false);

      Optional<RssFeedState> feedState = feedStateRepository.findByFeedUrl(feedUrl);
      HttpResponse<InputStream> feedResponse = fetchFeed(httpClient, feedUrl, feedState);

      if (feedResponse.statusCode() == 304) {
        closeQuietly(feedResponse.body());
        log.info("RSS feed unchanged (304), ending run: {}", feedUrl);
        progress.setTotal(0);
        progress.complete();
        return;
      }
      if (feedResponse.statusCode() != 200) {
        closeQuietly(feedResponse.body());
        progress.fail(
            "Der RSS-Feed konnte nicht abgerufen werden: HTTP " + feedResponse.statusCode());
        return;
      }

      List<RssFeedEntry> entries;
      try (InputStream body = feedResponse.body()) {
        byte[] feedBytes = readBounded(body, properties.maxFeedSizeBytes());
        entries = feedParser.parse(new ByteArrayInputStream(feedBytes));
      } catch (RssFeedParseException e) {
        // German, user-facing message straight from the parser (PR #474 review) - the only
        // trace an operator has for e.g. undefined HTML entities turning a feed unparseable.
        log.warn("RSS feed did not parse: {}", feedUrl, e);
        progress.fail(e.getMessage());
        return;
      } catch (FeedTooLargeException e) {
        progress.fail(
            "Der RSS-Feed überschreitet die zulässige Größe von "
                + properties.maxFeedSizeBytes()
                + " Byte.");
        return;
      }

      // #490 review, finding 3: whether entries were deferred (dropped by the max-entries
      // truncation below, or skipped because the remote end rejected/failed to hand over a
      // detail page) decides whether the feed's ETag/Last-Modified may be persisted at all -
      // see the saveFeedState call below for why.
      boolean truncated = entries.size() > properties.maxEntries();
      int totalFound = entries.size();
      if (truncated) {
        log.info(
            "RSS feed {} carries {} entries, processing only the first {} (opaa.indexing.rss.max-entries)",
            feedUrl,
            totalFound,
            properties.maxEntries());
        entries = entries.subList(0, properties.maxEntries());
      }
      progress.setTotal(entries.size());
      progress.report();

      var anyEntryDeferred = new AtomicBoolean(truncated);
      for (RssFeedEntry entry : entries) {
        processEntry(httpClient, entry, targetLibrary, progress, anyEntryDeferred);
        progress.report();
      }

      // #490 review, finding 3: an ETag/Last-Modified saved after a run that deferred entries
      // (truncation, or a detail page the remote end rejected/failed to hand over) would let the
      // *next* run's feed-level 304 permanently hide those entries - even once the remote side
      // (e.g. a bot-protection challenge) stops rejecting them. The feed's conditional-GET state
      // is therefore only advanced once a run has actually accounted for every entry it saw.
      if (!anyEntryDeferred.get() && progress.failedCount() == 0) {
        saveFeedState(feedUrl, feedResponse);
      } else {
        log.info(
            "Not persisting RSS feed state for {} - this run deferred or failed at least one"
                + " entry, so a future 304 must not suppress it",
            feedUrl);
      }
      progress.complete();
    } catch (IOException | InterruptedException e) {
      log.error("RSS feed indexing failed: {}", feedUrl, e);
      progress.fail(e.getMessage());
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    } catch (Exception e) {
      log.error("RSS feed indexing failed unexpectedly: {}", feedUrl, e);
      progress.fail(e.getMessage());
    }
  }

  private void processEntry(
      HttpClient httpClient,
      RssFeedEntry entry,
      KnowledgeLibrary targetLibrary,
      IndexingRunProgress progress,
      AtomicBoolean anyEntryDeferred) {
    String entryUrl = entry.link();

    if (!isHttpOrHttps(entryUrl)) {
      log.warn(
          "Skipping RSS entry with a non-http(s) link (rejected by scheme check): {}", entryUrl);
      progress.recordSkipped();
      anyEntryDeferred.set(true);
      return;
    }

    Optional<Instant> publishedAt = entry.publishedAt();
    if (isUnchanged(entryUrl, publishedAt, targetLibrary)) {
      log.info("Skipping unchanged RSS entry (unchanged pubDate): {}", entryUrl);
      progress.recordSkipped();
      return;
    }

    delayBeforeRequest();

    String mainText;
    try {
      mainText = fetchMainText(httpClient, entryUrl);
    } catch (RejectedByRemoteException e) {
      // Deliberately kept apart from the catch below (ADR-0017): a 403/429/redirect to a
      // foreign host is the *other side* declining to hand over the page, not a failure of
      // OPAA's own processing - both still count as "skipped" (the job status has no separate
      // bucket), but with a log message that says which of the two happened.
      log.warn(
          "RSS detail page rejected by remote host, skipping: {} ({})", entryUrl, e.getMessage());
      progress.recordSkipped();
      anyEntryDeferred.set(true);
      return;
    } catch (UnsupportedContentTypeException e) {
      // #490 review, finding 2: a <link> pointing straight at a PDF (or any non-HTML content)
      // must not be pushed through Jsoup and indexed as garbled binary text - attachments are
      // #468's job, not this one's.
      log.warn(
          "Skipping RSS detail page with an unsupported content type, skipping: {} ({})",
          entryUrl,
          e.getMessage());
      progress.recordSkipped();
      anyEntryDeferred.set(true);
      return;
    } catch (IOException | InterruptedException e) {
      log.warn("RSS detail page unreachable, skipping: {} ({})", entryUrl, e.getMessage());
      progress.recordSkipped();
      anyEntryDeferred.set(true);
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return;
    }

    if (mainText == null || mainText.isBlank()) {
      log.warn("RSS detail page yielded no extractable text, skipping: {}", entryUrl);
      progress.recordSkipped();
      anyEntryDeferred.set(true);
      return;
    }

    try {
      FileProcessingResult result =
          fileProcessingService.processRssEntry(
              mainText,
              entry.title(),
              entryUrl,
              publishedAt.map(Instant::toString).orElse(null),
              targetLibrary);
      if (result == FileProcessingResult.SKIPPED) {
        progress.recordSkipped();
      } else {
        progress.recordProcessed();
        log.info("Indexed RSS entry: {}", entryUrl);
      }
    } catch (Exception e) {
      log.error("Failed to process RSS entry: {}", entryUrl, e);
      progress.recordFailed();
    } catch (Error e) {
      log.error("Fatal error while processing RSS entry: {}", entryUrl, e);
      progress.recordFailed();
    }
  }

  private HttpResponse<InputStream> fetchFeed(
      HttpClient httpClient, String feedUrl, Optional<RssFeedState> feedState)
      throws IOException, InterruptedException {
    HttpRequest.Builder reqBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(feedUrl))
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", properties.userAgent())
            .GET();
    feedState.ifPresent(
        state -> {
          if (state.getEtag() != null) {
            reqBuilder.header("If-None-Match", state.getEtag());
          }
          if (state.getLastModified() != null) {
            reqBuilder.header("If-Modified-Since", state.getLastModified());
          }
        });
    return httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
  }

  private void saveFeedState(String feedUrl, HttpResponse<InputStream> feedResponse) {
    String etag = feedResponse.headers().firstValue("ETag").orElse(null);
    String lastModified = feedResponse.headers().firstValue("Last-Modified").orElse(null);
    if (etag == null && lastModified == null) {
      return;
    }
    RssFeedState state =
        feedStateRepository
            .findByFeedUrl(feedUrl)
            .orElseGet(() -> new RssFeedState(feedUrl, null, null));
    state.setEtag(etag);
    state.setLastModified(lastModified);
    state.setUpdatedAt(Instant.now());
    feedStateRepository.save(state);
  }

  /**
   * Fetches a single entry's detail page and reduces it to its main content's text (#467). {@code
   * nav}/{@code header}/{@code footer}/menu-ish elements are stripped before the configured
   * selector is applied, so boilerplate that happens to sit inside the matched main element (a skip
   * link, a "share this article" bar) does not survive either.
   */
  private String fetchMainText(HttpClient httpClient, String entryUrl)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(entryUrl))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", properties.userAgent())
            .GET()
            .build();

    HttpResponse<InputStream> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

    // #490 review, finding 4: every path below - the three early rejections and the ordinary
    // 200 - must close the response body. try-with-resources around the whole evaluation (rather
    // than only around the byte-reading branch, as before) closes it on every exit, including the
    // three throws, instead of leaking an open connection until GC gets around to it.
    try (InputStream body = response.body()) {
      if (response.statusCode() == 403 || response.statusCode() == 429) {
        throw new RejectedByRemoteException("HTTP " + response.statusCode());
      }
      if (isForeignHostRedirect(entryUrl, response.uri())) {
        throw new RejectedByRemoteException("redirected to a foreign host: " + response.uri());
      }
      if (response.statusCode() != 200) {
        throw new IOException("HTTP " + response.statusCode() + " for URL: " + entryUrl);
      }

      String contentType = response.headers().firstValue("Content-Type").orElse(null);
      if (!isHtmlContentType(contentType)) {
        // #490 review, finding 2: a <link> pointing straight at a PDF (or anything else that is
        // not HTML) must never be pushed through Jsoup - attachments are #468's job.
        throw new UnsupportedContentTypeException(
            contentType != null ? contentType : "(kein Content-Type)");
      }

      byte[] pageBytes;
      try {
        pageBytes = readBounded(body, properties.maxPageSizeBytes());
      } catch (FeedTooLargeException e) {
        throw new IOException(
            "Detail page exceeds the configured limit of "
                + properties.maxPageSizeBytes()
                + " bytes: "
                + entryUrl);
      }

      // #490 review, finding 1: the server's declared charset (Content-Type's charset
      // parameter) wins when present; otherwise Jsoup.parse(InputStream, ...) itself detects the
      // charset from a BOM or a <meta> tag and falls back to UTF-8 - never a hardcoded
      // StandardCharsets.UTF_8, which silently mangled e.g. ISO-8859-1 pages into U+FFFD.
      org.jsoup.nodes.Document htmlDoc =
          Jsoup.parse(new ByteArrayInputStream(pageBytes), charsetNameFrom(contentType), entryUrl);
      // Boilerplate removal (#467 acceptance criteria): nav/header/footer/menu-ish elements
      // never survive into the index, regardless of whether they sit inside or outside the
      // matched main element below.
      htmlDoc
          .select(
              "nav, header, footer, [role=navigation], [role=banner], [role=contentinfo],"
                  + " .nav, .navigation, .menu, .breadcrumb, script, style, noscript")
          .remove();

      Element main = htmlDoc.selectFirst(properties.mainContentSelector());
      Element content = main != null ? main : htmlDoc.body();
      return content != null ? content.text() : "";
    }
  }

  /** Whether {@code contentType} (the raw {@code Content-Type} header value) denotes HTML. */
  private static boolean isHtmlContentType(String contentType) {
    if (contentType == null) {
      return false;
    }
    String mediaType = contentType.split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT);
    return mediaType.equals("text/html") || mediaType.equals("application/xhtml+xml");
  }

  /**
   * Extracts the {@code charset} parameter from a {@code Content-Type} header value, or {@code
   * null} when absent - {@link Jsoup#parse(InputStream, String, String)} treats {@code null} as
   * "detect from the document itself" (#490 review, finding 1).
   */
  private static String charsetNameFrom(String contentType) {
    if (contentType == null) {
      return null;
    }
    for (String part : contentType.split(";")) {
      String trimmed = part.strip();
      if (trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("charset=")) {
        String charset = trimmed.substring("charset=".length()).strip();
        // Some servers quote the value ("charset=\"iso-8859-1\"") - Jsoup expects a bare name.
        if (charset.length() >= 2 && charset.startsWith("\"") && charset.endsWith("\"")) {
          charset = charset.substring(1, charset.length() - 1);
        }
        return charset.isBlank() ? null : charset;
      }
    }
    return null;
  }

  /**
   * Whether {@code finalUri} landed on a different host than {@code originalUrl} - the signature of
   * a bot-protection challenge page a feed operator's detail page redirected to (#467, ADR-0017
   * motivation), distinguished from an ordinary same-host redirect (e.g. {@code http} to {@code
   * https}, or a trailing slash).
   */
  private boolean isForeignHostRedirect(String originalUrl, URI finalUri) {
    try {
      URI originalUri = new URI(originalUrl);
      return originalUri.getHost() != null
          && finalUri.getHost() != null
          && !originalUri.getHost().equalsIgnoreCase(finalUri.getHost());
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private void delayBeforeRequest() {
    if (properties.requestDelayMs() <= 0) {
      return;
    }
    try {
      Thread.sleep(properties.requestDelayMs());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static boolean isHttpOrHttps(String url) {
    if (url == null) {
      return false;
    }
    String lowerCased = url.strip().toLowerCase(java.util.Locale.ROOT);
    return lowerCased.startsWith("http://") || lowerCased.startsWith("https://");
  }

  /**
   * Checks if an RSS entry's document is unchanged based on its {@code pubDate}, before its detail
   * page is ever requested (ADR-0017) - mirrors {@link UrlIndexingExecutor#isUnchanged}. A missing
   * {@code pubDate} is treated as "changed": there is nothing to compare against, so the entry is
   * (re-)fetched and the SHA-256 checksum inside {@link FileProcessingService#processRssEntry}
   * becomes the deciding change signal instead.
   */
  private boolean isUnchanged(
      String entryUrl, Optional<Instant> publishedAt, KnowledgeLibrary targetLibrary) {
    if (publishedAt.isEmpty()) {
      return false;
    }
    Optional<Document> existing = documentRepository.findByFilePath(entryUrl);
    // #490 review, finding 8: mirrors the same check FileProcessingService#processRssEntry makes
    // (library changed -> not unchanged) - without it, moving the target library never took
    // effect for an entry whose pubDate is otherwise unchanged, because this check runs before
    // the detail page (and processRssEntry) is ever reached.
    return existing.isPresent()
        && publishedAt.get().toString().equals(existing.get().getLastModifiedRemote())
        && existing.get().getStatus() == DocumentStatus.INDEXED
        && targetLibrary.getId().equals(existing.get().getLibraryId());
  }

  /**
   * Reads at most {@code maxBytes} from {@code in}, throwing {@link FeedTooLargeException} the
   * moment a further byte would exceed the limit - enforced while streaming, not after the full
   * response has already been downloaded (PR #474 review of {@link RssFeedParser}, which itself
   * enforces no such limit).
   */
  private static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
    byte[] probe = in.readNBytes(Math.toIntExact(Math.min(maxBytes + 1, Integer.MAX_VALUE)));
    if (probe.length > maxBytes) {
      throw new FeedTooLargeException();
    }
    return probe;
  }

  /**
   * Closes a response body on a path that never reads it (a rejection before any bytes are
   * consumed) - {@code close()} on the {@code InputStream} {@link
   * HttpResponse.BodyHandlers#ofInputStream()} hands back is what actually releases the underlying
   * connection; skipping it on every early exit was PR #490 review finding 4 (up to {@code
   * max-entries} connections left open per run in the mass-rejection case).
   */
  private static void closeQuietly(InputStream in) {
    try {
      in.close();
    } catch (IOException e) {
      log.debug("Failed to close response body", e);
    }
  }

  /** Thrown by {@link #readBounded} when the configured byte limit is exceeded while streaming. */
  private static final class FeedTooLargeException extends RuntimeException {}

  /**
   * Thrown when the remote end itself declined to hand over a detail page (403/429, or a redirect
   * to a foreign host) - kept distinct from an ordinary {@link IOException} so the caller can log
   * and count it separately from a processing failure (ADR-0017's "Verhalten gegenüber fremden
   * Zielen").
   */
  private static final class RejectedByRemoteException extends RuntimeException {
    RejectedByRemoteException(String message) {
      super(message);
    }
  }

  /**
   * Thrown when a detail page's {@code Content-Type} is not HTML (#490 review, finding 2) - e.g. a
   * {@code <link>} pointing straight at a PDF. Kept distinct from {@link
   * RejectedByRemoteException}: the remote end answered normally here, it just did not hand over
   * something this executor can extract text from. Attachments are #468's job.
   */
  private static final class UnsupportedContentTypeException extends RuntimeException {
    UnsupportedContentTypeException(String actualContentType) {
      super(actualContentType);
    }
  }
}
