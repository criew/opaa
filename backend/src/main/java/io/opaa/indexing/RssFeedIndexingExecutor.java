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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
 *
 * <p><b>Attachments (#468).</b> Once an entry's detail page has yielded its main text, the same
 * content area is searched for attachments using the configured {@link
 * IndexingProperties.Rss#attachmentProfile()} ({@link AttachmentProfile}) - {@code GENERIC} by
 * default, {@code GSB} for the Government Site Builder's query-parameter attachment pattern. Every
 * candidate is downloaded (bounded by {@link IndexingProperties.Rss#maxAttachmentSizeBytes()},
 * subject to the same politeness delay as detail pages) and handed into the same shared processing
 * chain as an {@code HTTP_DIRECTORY} file via {@link
 * FileProcessingService#processUrlFile(java.nio.file.Path, String, String, String, long,
 * KnowledgeLibrary, DocumentSourceType, String)}, with the entry's own URL recorded as {@code
 * sourceEntryUrl} so the attachment's origin stays traceable. An attachment failure (unreachable,
 * oversized, unsupported format) is logged and skipped; unlike an entry-level failure it never
 * affects this entry's own processed/skipped/failed outcome or the run as a whole - it does,
 * however, mark the run as having deferred something (see {@link #processAttachments}), the same
 * way a lost entry does.
 *
 * <p><b>Attachments of an already-unchanged entry (#468, PR #492 review finding 1).</b> An entry
 * whose {@code pubDate} is unchanged still gets a cheap detail-page-free skip - <em>unless</em> it
 * has no attachment documents yet ({@link DocumentRepository#existsBySourceEntryUrl}), in which
 * case its detail page is fetched once more for attachments alone; see {@link
 * #processUnchangedEntry}. Without this, an entry indexed before attachment support existed would
 * never get attachments discovered for it at all, since its {@code pubDate} never changes again.
 */
public class RssFeedIndexingExecutor implements SourceIndexingExecutor {

  private static final Logger log = LoggerFactory.getLogger(RssFeedIndexingExecutor.class);

  private final RssFeedParser feedParser;
  private final FileProcessingService fileProcessingService;
  private final IndexingJobService indexingJobService;
  private final DocumentRepository documentRepository;
  private final RssFeedStateRepository feedStateRepository;
  private final UrlFileDownloader attachmentDownloader;
  private final IndexingProperties.Rss properties;

  public RssFeedIndexingExecutor(
      RssFeedParser feedParser,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      DocumentRepository documentRepository,
      RssFeedStateRepository feedStateRepository,
      UrlFileDownloader attachmentDownloader,
      IndexingProperties properties) {
    this.feedParser = feedParser;
    this.fileProcessingService = fileProcessingService;
    this.indexingJobService = indexingJobService;
    this.documentRepository = documentRepository;
    this.feedStateRepository = feedStateRepository;
    this.attachmentDownloader = attachmentDownloader;
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
      processUnchangedEntry(httpClient, entryUrl, progress, anyEntryDeferred, targetLibrary);
      return;
    }

    delayBeforeRequest();

    DetailPage detailPage;
    try {
      detailPage = fetchDetailPage(httpClient, entryUrl);
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

    if (detailPage.mainText() == null || detailPage.mainText().isBlank()) {
      log.warn("RSS detail page yielded no extractable text, skipping: {}", entryUrl);
      progress.recordSkipped();
      anyEntryDeferred.set(true);
      return;
    }

    try {
      FileProcessingResult result =
          fileProcessingService.processRssEntry(
              detailPage.mainText(),
              entry.title(),
              entryUrl,
              publishedAt.map(Instant::toString).orElse(null),
              targetLibrary);
      if (result == FileProcessingResult.SKIPPED) {
        progress.recordSkipped();
      } else {
        progress.recordProcessed();
        log.info("Indexed RSS entry: {}", entryUrl);
        processAttachments(
            httpClient, detailPage.attachments(), entryUrl, targetLibrary, anyEntryDeferred);
      }
    } catch (Exception e) {
      log.error("Failed to process RSS entry: {}", entryUrl, e);
      progress.recordFailed();
    } catch (Error e) {
      log.error("Fatal error while processing RSS entry: {}", entryUrl, e);
      progress.recordFailed();
    }
  }

  /**
   * Handles an entry whose {@code pubDate} is unchanged (#468, PR #492 review finding 1). Before
   * this fix, an unchanged entry returned immediately - which meant an entry indexed *before*
   * attachment support existed, or before this feature's attachment profile was configured, never
   * had its attachments discovered at all: its {@code pubDate} never changes again, so {@link
   * #processEntry} would forever take this branch and never re-fetch the detail page attachments
   * are found on. This method closes that gap cheaply: it checks whether at least one attachment
   * document already exists for this entry ({@link DocumentRepository#existsBySourceEntryUrl}) and
   * only fetches the detail page - for attachments alone, the entry's own main text is not
   * reprocessed - when none do. An entry that already has its attachments stays as cheap as before
   * (no detail-page request at all).
   */
  private void processUnchangedEntry(
      HttpClient httpClient,
      String entryUrl,
      IndexingRunProgress progress,
      AtomicBoolean anyEntryDeferred,
      KnowledgeLibrary targetLibrary) {
    progress.recordSkipped();
    if (documentRepository.existsBySourceEntryUrl(entryUrl)) {
      log.info("Skipping unchanged RSS entry (unchanged pubDate): {}", entryUrl);
      return;
    }

    log.info(
        "RSS entry unchanged but has no attachment documents yet, fetching its detail page to"
            + " backfill attachments only: {}",
        entryUrl);
    delayBeforeRequest();
    DetailPage detailPage;
    try {
      detailPage = fetchDetailPage(httpClient, entryUrl);
    } catch (RejectedByRemoteException | UnsupportedContentTypeException e) {
      log.warn(
          "Could not fetch RSS detail page to backfill attachments, will retry on a future run:"
              + " {} ({})",
          entryUrl,
          e.getMessage());
      anyEntryDeferred.set(true);
      return;
    } catch (IOException | InterruptedException e) {
      log.warn(
          "RSS detail page unreachable while backfilling attachments, will retry on a future run:"
              + " {} ({})",
          entryUrl,
          e.getMessage());
      anyEntryDeferred.set(true);
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return;
    }
    processAttachments(
        httpClient, detailPage.attachments(), entryUrl, targetLibrary, anyEntryDeferred);
  }

  /**
   * Downloads and indexes every attachment {@code candidates} lists, up to {@link
   * IndexingProperties.Rss#maxAttachmentsPerEntry()} (#468). Never throws: a single attachment that
   * cannot be downloaded, exceeds the configured size limit, or turns out to be an unsupported
   * format is logged and skipped, exactly as the issue's acceptance criteria require ("Ein
   * Anlagen-Fehler bricht weder Eintrag noch Lauf ab") - it has no effect on {@code entryUrl}'s own
   * processed/skipped/failed outcome, which was already decided by the time this method runs.
   *
   * <p><b>{@code anyEntryDeferred} (PR #492 review, finding 2).</b> A lost attachment - too large,
   * unreachable, rejected, or cut off by {@link IndexingProperties.Rss#maxAttachmentsPerEntry()} -
   * marks the run the same way a deferred entry does: without this, {@code saveFeedState} could
   * persist the feed's ETag for a run that actually lost an attachment, and the entry's own {@code
   * pubDate} check would then suppress every future attempt to recover it (the #490 finding-3 class
   * of bug, one level down).
   */
  private void processAttachments(
      HttpClient httpClient,
      List<AttachmentCandidate> candidates,
      String entryUrl,
      KnowledgeLibrary targetLibrary,
      AtomicBoolean anyEntryDeferred) {
    int limit = Math.min(candidates.size(), properties.maxAttachmentsPerEntry());
    if (candidates.size() > limit) {
      log.info(
          "RSS entry {} carries {} attachments, processing only the first {}"
              + " (opaa.indexing.rss.max-attachments-per-entry)",
          entryUrl,
          candidates.size(),
          limit);
      anyEntryDeferred.set(true);
    }
    for (AttachmentCandidate candidate : candidates.subList(0, limit)) {
      delayBeforeRequest();
      processAttachment(httpClient, candidate, entryUrl, targetLibrary, anyEntryDeferred);
    }
  }

  /**
   * Downloads and indexes a single attachment. Deliberately never lets an exception escape - PR
   * #492 review, finding 11: an attachment that throws an unchecked exception (e.g. an unusual
   * malformed URL) would otherwise propagate out of {@link #processEntry}'s already-passed {@code
   * recordProcessed()} call and into its {@code catch (Exception e)}, counting the same entry as
   * both processed <em>and</em> failed.
   */
  private void processAttachment(
      HttpClient httpClient,
      AttachmentCandidate candidate,
      String entryUrl,
      KnowledgeLibrary targetLibrary,
      AtomicBoolean anyEntryDeferred) {
    UrlFileDownloader.DownloadedFile downloaded = null;
    try {
      downloaded =
          attachmentDownloader.downloadBounded(
              httpClient,
              candidate.url(),
              candidate.suggestedFileName(),
              properties.maxAttachmentSizeBytes(),
              properties.userAgent());

      String contentType = downloaded.contentType();
      if (isHtmlContentType(contentType)) {
        // #492 review, finding 3: an HTML response on what a profile identified as an attachment
        // link - a bot-protection challenge or a 200-status error page - must never be trusted
        // just because the *URL* carried a supported extension (GENERIC's case; GSB's candidates
        // never carry an extension to begin with, so they already went through
        // extensionForContentType, which has no HTML mapping and would already reject this).
        log.info(
            "Skipping RSS attachment that answered with HTML instead of a document (likely a"
                + " bot-protection or error page): {} (from entry {})",
            candidate.url(),
            entryUrl);
        anyEntryDeferred.set(true);
        return;
      }

      // The GSB profile's candidates carry no extension in their URL (#468) - resolved here, once
      // the response's actual Content-Type is known, rather than in AttachmentProfile itself,
      // which never downloads anything.
      String fileName = resolveFileName(candidate.suggestedFileName(), contentType);
      if (!SupportedDocumentFormats.isSupported(fileName)) {
        log.info(
            "Skipping RSS attachment with an unsupported format: {} (from entry {}, Content-Type"
                + " {})",
            candidate.url(),
            entryUrl,
            contentType);
        anyEntryDeferred.set(true);
        return;
      }

      // #492 review, finding 7: the downloaded temp file's own suffix reflects
      // candidate.suggestedFileName(), which for a GSB attachment carries no extension at all
      // (".tmp") - Files.probeContentType inside FileProcessingService#processUrlFile probes that
      // physical file, not the resolved fileName above, and would find nothing even though the
      // response's Content-Type was known all along. Renaming the temp file to match the resolved
      // name's extension lets that probe succeed the normal way, without changing
      // processUrlFile's signature.
      Path indexedFile = withMatchingExtension(downloaded.path(), fileName);

      long size = Files.size(indexedFile);
      fileProcessingService.processUrlFile(
          indexedFile,
          fileName,
          candidate.url(),
          null,
          size,
          targetLibrary,
          DocumentSourceType.RSS_FEED,
          entryUrl);
      log.info("Indexed RSS attachment: {} (from entry {})", candidate.url(), entryUrl);
    } catch (UrlFileDownloader.AttachmentTooLargeException e) {
      log.warn(
          "Skipping RSS attachment exceeding the size limit of {} bytes: {} (from entry {})",
          properties.maxAttachmentSizeBytes(),
          candidate.url(),
          entryUrl);
      anyEntryDeferred.set(true);
    } catch (UrlFileDownloader.ForeignHostRedirectException e) {
      log.warn(
          "RSS attachment redirected to a foreign host, skipping: {} (from entry {}, {})",
          candidate.url(),
          entryUrl,
          e.getMessage());
      anyEntryDeferred.set(true);
    } catch (IOException | InterruptedException e) {
      log.warn(
          "RSS attachment unreachable, skipping: {} (from entry {}, {})",
          candidate.url(),
          entryUrl,
          e.getMessage());
      anyEntryDeferred.set(true);
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    } catch (Exception e) {
      log.error(
          "Failed to process RSS attachment: {} (from entry {})", candidate.url(), entryUrl, e);
      anyEntryDeferred.set(true);
    } finally {
      if (downloaded != null) {
        try {
          Files.deleteIfExists(downloaded.path());
        } catch (IOException e) {
          log.warn("Failed to delete temp file: {}", downloaded.path(), e);
        }
      }
    }
  }

  /**
   * Appends an extension derived from {@code contentType} when {@code suggestedFileName} does not
   * already carry a supported one (#468, the Government Site Builder profile's case) - a no-op for
   * {@link AttachmentProfile#GENERIC} candidates, which always already carry one.
   */
  private static String resolveFileName(String suggestedFileName, String contentType) {
    if (SupportedDocumentFormats.isSupported(suggestedFileName)) {
      return suggestedFileName;
    }
    String extension = SupportedDocumentFormats.extensionForContentType(contentType);
    if (extension == null) {
      return suggestedFileName;
    }
    String baseName =
        suggestedFileName == null || suggestedFileName.isBlank() ? "attachment" : suggestedFileName;
    return baseName + extension;
  }

  /**
   * Renames {@code tempFile} to a new temp file carrying {@code fileName}'s own extension, when it
   * does not already have it (#492 review, finding 7). A no-op - returns {@code tempFile} unchanged
   * - whenever the extension already matches, which covers every {@link AttachmentProfile#GENERIC}
   * attachment (its candidates already carry a supported extension the download used verbatim).
   */
  private static Path withMatchingExtension(Path tempFile, String fileName) throws IOException {
    String desiredSuffix = extractExtension(fileName);
    if (tempFile.toString().toLowerCase(Locale.ROOT).endsWith(desiredSuffix)) {
      return tempFile;
    }
    Path renamed = Files.createTempFile("opaa-", desiredSuffix);
    Files.move(tempFile, renamed, StandardCopyOption.REPLACE_EXISTING);
    return renamed;
  }

  private static String extractExtension(String fileName) {
    if (fileName == null) {
      return ".tmp";
    }
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex >= 0) {
      return fileName.substring(dotIndex).toLowerCase(Locale.ROOT);
    }
    return ".tmp";
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
   * An entry's detail page, reduced to its main content's text and attachment candidates (#468).
   */
  private record DetailPage(String mainText, List<AttachmentCandidate> attachments) {}

  /**
   * Fetches a single entry's detail page and reduces it to its main content's text (#467), together
   * with every attachment the configured {@link AttachmentProfile} finds inside that same content
   * area (#468). {@code nav}/{@code header}/{@code footer}/menu-ish elements are stripped before
   * the configured selector is applied, so boilerplate that happens to sit inside the matched main
   * element (a skip link, a "share this article" bar) does not survive either, and is never
   * considered for attachments.
   */
  private DetailPage fetchDetailPage(HttpClient httpClient, String entryUrl)
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
      if (content == null) {
        return new DetailPage("", List.of());
      }
      List<AttachmentCandidate> attachments =
          properties.attachmentProfile().findAttachments(content, URI.create(entryUrl));
      return new DetailPage(content.text(), attachments);
    }
  }

  /** Whether {@code contentType} (the raw {@code Content-Type} header value) denotes HTML. */
  private static boolean isHtmlContentType(String contentType) {
    if (contentType == null) {
      return false;
    }
    String mediaType = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
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
      if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
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
    String lowerCased = url.strip().toLowerCase(Locale.ROOT);
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
