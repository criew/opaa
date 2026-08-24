package io.opaa.indexing;

import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;

/**
 * Executes indexing runs for {@link IndexingSourceType#RSS_FEED} (ADR-0017): fetches an RSS 2.0
 * feed, resolves every entry's detail page and hands the page's main text - not the whole page -
 * into the shared processing chain via {@link FileProcessingService#processRssEntry}.
 *
 * <p><b>Two-stage change detection.</b> The feed itself is fetched with a conditional {@code GET}
 * (ETag/{@code If-Modified-Since}, tracked per library and feed URL in {@link RssFeedState}) - an
 * unchanged feed ends the run after a single {@code 304} response. Every entry is then checked
 * against its stored {@code pubDate} before its detail page is requested (mirrors {@link
 * UrlIndexingExecutor#isUnchanged}); the SHA-256 checksum inside {@link
 * FileProcessingService#processRssEntry} is the final, content-based layer once a page is fetched.
 *
 * <p><b>No deletion by absence (ADR-0017, decision 5).</b> An entry that has scrolled out of the
 * feed's window is not touched here.
 *
 * <p>Entry- and byte-size limits, a link scheme check ({@code http}/{@code https} only), a minimum
 * delay between detail-page requests and a configurable {@code User-Agent} come from {@link
 * IndexingProperties.Rss}. A rejected, oversized or unreachable entry never aborts the run; it is
 * skipped, counted and logged.
 *
 * <p><b>Attachments.</b> Once an entry's detail page has yielded its main text, the same content
 * area is searched for attachments using the configured {@link
 * IndexingProperties.Rss#attachmentProfile()} ({@link AttachmentProfile}). Every candidate is
 * downloaded (bounded by {@link IndexingProperties.Rss#maxAttachmentSizeBytes()}) and handed into
 * the shared processing chain via {@link FileProcessingService#processUrlFile(java.nio.file.Path,
 * String, String, String, long, KnowledgeLibrary, DocumentSourceType, String)}, with the entry's
 * own URL recorded as {@code sourceEntryUrl}. An attachment failure never affects the entry's own
 * outcome, but marks the run as having deferred something (see {@link #processAttachments}). An
 * entry whose {@code pubDate} is unchanged still gets its detail page fetched once for attachments
 * alone when it has none yet ({@link DocumentRepository#existsBySourceEntryUrl}, see {@link
 * #processUnchangedEntry}) - otherwise it would never receive attachments discovered after it was
 * first indexed.
 *
 * <p><b>Credentials and proxy.</b> {@code targetLibrary}'s {@code sourceCredentials} (Basic Auth)
 * and {@code sourceProxy} are applied to every request this executor makes, mirroring {@link
 * UrlIndexingExecutor#toUrlIndexingRequest}. The {@code Authorization} header and {@code
 * sourceInsecureSsl} relaxation are withheld for any target outside the feed's own origin ({@link
 * #authHeaderForTarget}, {@link #httpClientForTarget}) - an entry's {@code <link>} or an attachment
 * URL is content the feed operator controls, not a target the library owner vouches for.
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
  private final IndexingRunEventRepository indexingRunEventRepository;
  private final TargetAddressValidator targetAddressValidator;
  private final LibraryStorageQuotaService storageQuotaService;

  public RssFeedIndexingExecutor(
      RssFeedParser feedParser,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      DocumentRepository documentRepository,
      RssFeedStateRepository feedStateRepository,
      UrlFileDownloader attachmentDownloader,
      IndexingProperties properties,
      IndexingRunEventRepository indexingRunEventRepository,
      TargetAddressValidator targetAddressValidator,
      LibraryStorageQuotaService storageQuotaService) {
    this.feedParser = feedParser;
    this.fileProcessingService = fileProcessingService;
    this.indexingJobService = indexingJobService;
    this.documentRepository = documentRepository;
    this.feedStateRepository = feedStateRepository;
    this.attachmentDownloader = attachmentDownloader;
    this.properties = properties.rss();
    this.indexingRunEventRepository = indexingRunEventRepository;
    this.targetAddressValidator = targetAddressValidator;
    this.storageQuotaService = storageQuotaService;
  }

  @Override
  public IndexingSourceType sourceType() {
    return IndexingSourceType.RSS_FEED;
  }

  @Override
  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary) {
    var progress = new IndexingRunProgress(indexingJobService, jobId);
    var events =
        new IndexingRunEventRecorder(indexingRunEventRepository, indexingJobService, jobId);
    // ADR-0018: the feed's address is the library's own sourceUrl, not a per-request field.
    String feedUrl = targetLibrary.getSourceUrl();

    try {
      ProxyAndCredentials config;
      try {
        config =
            ProxyAndCredentials.parse(
                targetLibrary.getSourceProxy(), targetLibrary.getSourceCredentials());
      } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
        progress.fail(e.getMessage());
        return;
      }
      String authHeader =
          AutoindexCrawlerService.buildAuthHeader(config.username(), config.password());

      // sourceInsecureSsl must never weaken certificate validation for a target the feed's own
      // content points at (an entry's <link>, an attachment URL) once it leaves the feed's origin -
      // only the feed's own origin is a source the library owner vouches for. secureClient always
      // validates normally; insecureClient relaxes validation only when the library asks for it and
      // is used exclusively for same-origin requests ({@link #httpClientForTarget}).
      HttpClient secureClient =
          AutoindexCrawlerService.buildHttpClient(config.proxyHost(), config.proxyPort(), false);
      HttpClient insecureClient =
          targetLibrary.isSourceInsecureSsl()
              ? AutoindexCrawlerService.buildHttpClient(
                  config.proxyHost(), config.proxyPort(), true)
              : secureClient;

      // Keyed by (libraryId, feedUrl), not feedUrl alone - see RssFeedState's Javadoc.
      Optional<RssFeedState> feedState =
          feedStateRepository.findByLibraryIdAndFeedUrl(targetLibrary.getId(), feedUrl);
      HttpResponse<InputStream> feedResponse =
          fetchFeed(insecureClient, feedUrl, feedState, authHeader);

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
        // German, user-facing message straight from the parser.
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

      // Whether entries were deferred (truncated below, or skipped because the remote end
      // rejected/failed to hand over a detail page) decides whether the feed's ETag/Last-Modified
      // may be persisted at all - see the saveFeedState call below.
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
        processEntry(
            secureClient,
            insecureClient,
            entry,
            targetLibrary,
            progress,
            events,
            anyEntryDeferred,
            authHeader,
            feedUrl);
        progress.report();
      }

      // An ETag/Last-Modified saved after a run that deferred entries would let a future 304
      // permanently hide those entries - the conditional-GET state only advances once a run has
      // accounted for every entry it saw.
      if (!anyEntryDeferred.get() && progress.failedCount() == 0) {
        saveFeedState(targetLibrary.getId(), feedUrl, feedResponse);
      } else {
        log.info(
            "Not persisting RSS feed state for {} - this run deferred or failed at least one"
                + " entry, so a future 304 must not suppress it",
            feedUrl);
      }
      events.finalizeRun();
      progress.complete();
    } catch (IOException | InterruptedException e) {
      log.error("RSS feed indexing failed: {}", feedUrl, e);
      events.finalizeRun();
      progress.fail(e.getMessage());
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    } catch (DataIntegrityViolationException e) {
      // fk_rss_feed_state_library makes the delete-during-run race visible as a constraint
      // violation - the target library was deleted between this run starting and saveFeedState's
      // write. The raw JDBC/Hibernate message must never reach the user-facing run status.
      log.error("RSS feed indexing failed - target library no longer exists: {}", feedUrl, e);
      events.finalizeRun();
      progress.fail("Die Bibliothek wurde während des Laufs gelöscht.");
    } catch (Exception e) {
      log.error("RSS feed indexing failed unexpectedly: {}", feedUrl, e);
      events.finalizeRun();
      progress.fail(e.getMessage());
    }
  }

  private void processEntry(
      HttpClient secureClient,
      HttpClient insecureClient,
      RssFeedEntry entry,
      KnowledgeLibrary targetLibrary,
      IndexingRunProgress progress,
      IndexingRunEventRecorder events,
      AtomicBoolean anyEntryDeferred,
      String authHeader,
      String feedUrl) {
    String entryUrl = entry.link();

    if (!isHttpOrHttps(entryUrl)) {
      log.warn(
          "Skipping RSS entry with a non-http(s) link (rejected by scheme check): {}", entryUrl);
      events.record(
          IndexingEventCategory.REJECTED,
          "Verknüpfung mit nicht unterstütztem Schema abgelehnt",
          entryUrl);
      progress.recordSkipped();
      anyEntryDeferred.set(true);
      return;
    }

    // An http(s)-prefixed link can still be syntactically invalid; URI.create(entryUrl) inside
    // fetchDetailPage would then throw IllegalArgumentException uncaught, ending the whole run
    // instead of just this entry.
    if (!isValidUri(entryUrl)) {
      log.warn("Skipping RSS entry with a syntactically invalid link: {}", entryUrl);
      events.record(
          IndexingEventCategory.REJECTED, "Verknüpfung mit ungültiger URL abgelehnt", entryUrl);
      progress.recordSkipped();
      anyEntryDeferred.set(true);
      return;
    }

    Optional<Instant> publishedAt = entry.publishedAt();
    if (isUnchanged(entryUrl, publishedAt, targetLibrary)) {
      processUnchangedEntry(
          secureClient,
          insecureClient,
          entryUrl,
          progress,
          events,
          anyEntryDeferred,
          targetLibrary,
          authHeader,
          feedUrl);
      return;
    }

    delayBeforeRequest();

    DetailPage detailPage;
    try {
      detailPage =
          fetchDetailPage(
              secureClient,
              insecureClient,
              feedUrl,
              entryUrl,
              authHeaderForTarget(authHeader, feedUrl, entryUrl));
    } catch (RejectedByRemoteException e) {
      // Kept apart from the catch below (ADR-0017): a 403/429/redirect to a foreign host is the
      // other side declining to hand over the page, not a processing failure. Both count as
      // "skipped". The German event message is e.userMessage(), never e.getMessage() - the latter
      // can carry the raw, unsanitized redirect target and must never reach the UI.
      log.warn(
          "RSS detail page rejected by remote host, skipping: {} ({})", entryUrl, e.getMessage());
      events.record(IndexingEventCategory.REJECTED, e.userMessage(), entryUrl);
      progress.recordSkipped();
      anyEntryDeferred.set(true);
      return;
    } catch (TargetAddressValidator.TargetAddressBlockedException e) {
      // e.getMessage() is already German, user-facing and safe to show as-is (see
      // TargetAddressValidator's Javadoc).
      log.warn("RSS detail page target rejected, skipping: {} ({})", entryUrl, e.getMessage());
      events.record(IndexingEventCategory.REJECTED, e.getMessage(), entryUrl);
      progress.recordSkipped();
      anyEntryDeferred.set(true);
      return;
    } catch (UnsupportedContentTypeException e) {
      // A <link> pointing straight at a PDF (or any non-HTML content) must not be pushed through
      // Jsoup and indexed as garbled binary text - attachments are handled separately.
      log.warn(
          "Skipping RSS detail page with an unsupported content type, skipping: {} ({})",
          entryUrl,
          e.getMessage());
      events.record(
          IndexingEventCategory.UNSUPPORTED_FORMAT,
          "Inhaltstyp der Detailseite wird nicht unterstützt",
          entryUrl);
      progress.recordSkipped();
      anyEntryDeferred.set(true);
      return;
    } catch (IOException | InterruptedException e) {
      log.warn("RSS detail page unreachable, skipping: {} ({})", entryUrl, e.getMessage());
      events.record(IndexingEventCategory.UNREACHABLE, "Detailseite nicht erreichbar", entryUrl);
      progress.recordSkipped();
      anyEntryDeferred.set(true);
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return;
    } catch (IllegalArgumentException e) {
      // entryUrl already passed isValidUri above, but a redirect hop's own Location header is
      // server-controlled and can still make currentUri.resolve(location) throw here.
      log.warn(
          "Skipping RSS entry whose detail-page redirect could not be resolved: {} ({})",
          entryUrl,
          e.getMessage());
      events.record(
          IndexingEventCategory.REJECTED, "Weiterleitung der Detailseite ungültig", entryUrl);
      progress.recordSkipped();
      anyEntryDeferred.set(true);
      return;
    }

    if (detailPage.mainText() == null || detailPage.mainText().isBlank()) {
      log.warn("RSS detail page yielded no extractable text, skipping: {}", entryUrl);
      events.record(IndexingEventCategory.UNSUPPORTED_FORMAT, "Kein Inhalt extrahierbar", entryUrl);
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
      if (result == FileProcessingResult.QUOTA_EXCEEDED) {
        // See AsyncIndexingExecutor's own handling of this outcome.
        events.record(
            IndexingEventCategory.REJECTED,
            storageQuotaService.quotaExceededMessage(targetLibrary.getId()),
            entryUrl);
        progress.recordSkipped();
      } else if (result == FileProcessingResult.SKIPPED) {
        progress.recordSkipped();
      } else {
        progress.recordProcessed();
        log.info("Indexed RSS entry: {}", entryUrl);
        processAttachments(
            secureClient,
            insecureClient,
            detailPage.attachments(),
            entryUrl,
            targetLibrary,
            anyEntryDeferred,
            progress,
            events,
            authHeader,
            feedUrl);
      }
    } catch (Exception e) {
      log.error("Failed to process RSS entry: {}", entryUrl, e);
      events.record(IndexingEventCategory.ERROR, "Verarbeitung fehlgeschlagen", entryUrl);
      progress.recordFailed();
    } catch (Error e) {
      log.error("Fatal error while processing RSS entry: {}", entryUrl, e);
      events.record(IndexingEventCategory.ERROR, "Verarbeitung fehlgeschlagen", entryUrl);
      progress.recordFailed();
    }
  }

  /**
   * Handles an entry whose {@code pubDate} is unchanged. Since the pubDate never changes again,
   * {@link #processEntry} would otherwise never re-fetch the detail page attachments are found on.
   * This method fetches the detail page for attachments alone (the entry's own text is not
   * reprocessed) only when no attachment document exists yet for it ({@link
   * DocumentRepository#existsBySourceEntryUrl}); an entry that already has its attachments stays as
   * cheap as before.
   */
  private void processUnchangedEntry(
      HttpClient secureClient,
      HttpClient insecureClient,
      String entryUrl,
      IndexingRunProgress progress,
      IndexingRunEventRecorder events,
      AtomicBoolean anyEntryDeferred,
      KnowledgeLibrary targetLibrary,
      String authHeader,
      String feedUrl) {
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
      detailPage =
          fetchDetailPage(
              secureClient,
              insecureClient,
              feedUrl,
              entryUrl,
              authHeaderForTarget(authHeader, feedUrl, entryUrl));
    } catch (RejectedByRemoteException e) {
      log.warn(
          "Could not fetch RSS detail page to backfill attachments, will retry on a future run:"
              + " {} ({})",
          entryUrl,
          e.getMessage());
      events.record(
          IndexingEventCategory.REJECTED,
          e.userMessage() + " (beim Nachladen von Anlagen)",
          entryUrl);
      anyEntryDeferred.set(true);
      return;
    } catch (TargetAddressValidator.TargetAddressBlockedException e) {
      log.warn(
          "Could not fetch RSS detail page to backfill attachments, its target was rejected, will"
              + " retry on a future run: {} ({})",
          entryUrl,
          e.getMessage());
      events.record(
          IndexingEventCategory.REJECTED,
          e.getMessage() + " (beim Nachladen von Anlagen)",
          entryUrl);
      anyEntryDeferred.set(true);
      return;
    } catch (UnsupportedContentTypeException e) {
      log.warn(
          "Could not fetch RSS detail page to backfill attachments, will retry on a future run:"
              + " {} ({})",
          entryUrl,
          e.getMessage());
      events.record(
          IndexingEventCategory.UNSUPPORTED_FORMAT,
          "Inhaltstyp der Detailseite wird nicht unterstützt (Anlagen konnten nicht nachgeladen"
              + " werden)",
          entryUrl);
      anyEntryDeferred.set(true);
      return;
    } catch (IOException | InterruptedException e) {
      log.warn(
          "RSS detail page unreachable while backfilling attachments, will retry on a future run:"
              + " {} ({})",
          entryUrl,
          e.getMessage());
      events.record(
          IndexingEventCategory.UNREACHABLE,
          "Detailseite beim Nachladen von Anlagen nicht erreichbar",
          entryUrl);
      anyEntryDeferred.set(true);
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return;
    } catch (IllegalArgumentException e) {
      // Mirrors processEntry's identical catch: a redirect hop's own Location header can still
      // make currentUri.resolve(location) throw here.
      log.warn(
          "Could not fetch RSS detail page to backfill attachments, its redirect could not be"
              + " resolved, will retry on a future run: {} ({})",
          entryUrl,
          e.getMessage());
      events.record(
          IndexingEventCategory.REJECTED,
          "Weiterleitung der Detailseite beim Nachladen von Anlagen ungültig",
          entryUrl);
      anyEntryDeferred.set(true);
      return;
    }
    processAttachments(
        secureClient,
        insecureClient,
        detailPage.attachments(),
        entryUrl,
        targetLibrary,
        anyEntryDeferred,
        progress,
        events,
        authHeader,
        feedUrl);
  }

  /**
   * Downloads and indexes every attachment {@code candidates} lists, up to {@link
   * IndexingProperties.Rss#maxAttachmentsPerEntry()}. Never throws: a lost attachment (too large,
   * unreachable, rejected, unsupported format, or cut off by the per-entry limit) is logged and
   * skipped, with no effect on {@code entryUrl}'s own processed/skipped/failed outcome - but marks
   * {@code anyEntryDeferred}, the same way a deferred entry does, so {@code saveFeedState} does not
   * persist an ETag that would suppress a future retry of the lost attachment.
   */
  private void processAttachments(
      HttpClient secureClient,
      HttpClient insecureClient,
      List<AttachmentCandidate> candidates,
      String entryUrl,
      KnowledgeLibrary targetLibrary,
      AtomicBoolean anyEntryDeferred,
      IndexingRunProgress progress,
      IndexingRunEventRecorder events,
      String authHeader,
      String feedUrl) {
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
      processAttachment(
          secureClient,
          insecureClient,
          candidate,
          entryUrl,
          targetLibrary,
          anyEntryDeferred,
          progress,
          events,
          authHeader,
          feedUrl);
    }
  }

  /**
   * Downloads and indexes a single attachment. Deliberately never lets an exception escape:
   * otherwise it would propagate out of {@link #processEntry}'s already-passed {@code
   * recordProcessed()} call into its own {@code catch (Exception e)}, counting the same entry as
   * both processed and failed.
   */
  private void processAttachment(
      HttpClient secureClient,
      HttpClient insecureClient,
      AttachmentCandidate candidate,
      String entryUrl,
      KnowledgeLibrary targetLibrary,
      AtomicBoolean anyEntryDeferred,
      IndexingRunProgress progress,
      IndexingRunEventRecorder events,
      String authHeader,
      String feedUrl) {
    UrlFileDownloader.DownloadedFile downloaded = null;
    try {
      // An attachment candidate's own URL is content the feed operator controls, exactly like an
      // entry's <link> (see #httpClientForTarget) - sourceInsecureSsl must not weaken certificate
      // validation once it points off the feed's own origin.
      HttpClient client =
          httpClientForTarget(secureClient, insecureClient, feedUrl, candidate.url());
      downloaded =
          attachmentDownloader.downloadBounded(
              client,
              candidate.url(),
              candidate.suggestedFileName(),
              properties.maxAttachmentSizeBytes(),
              properties.userAgent(),
              authHeaderForTarget(authHeader, feedUrl, candidate.url()));

      String contentType = downloaded.contentType();
      if (isHtmlContentType(contentType)) {
        // An HTML response on what a profile identified as an attachment link - a bot-protection
        // challenge or a 200-status error page - must never be trusted just because the URL
        // carried a supported extension.
        log.info(
            "Skipping RSS attachment that answered with HTML instead of a document (likely a"
                + " bot-protection or error page): {} (from entry {})",
            candidate.url(),
            entryUrl);
        events.record(
            IndexingEventCategory.REJECTED,
            "Anlage antwortete mit HTML statt einem Dokument (vermutlich Bot-Schutz)",
            candidate.url());
        anyEntryDeferred.set(true);
        return;
      }

      // The GSB profile's candidates carry no extension in their URL - resolved here, once the
      // response's actual Content-Type is known. Only a display name / hint from here on; the
      // accept/reject decision below is made from the downloaded bytes.
      String fileName = resolveFileName(candidate.suggestedFileName(), contentType);

      // Caught here, not by the broader catch (IOException | InterruptedException e) below - that
      // one reports "Anlage nicht erreichbar", which would be misleading for a read failure on a
      // file already downloaded; the remote end answered just fine.
      String detectedMimeType;
      try {
        detectedMimeType = SupportedDocumentFormats.detectMediaType(downloaded.path());
      } catch (IOException e) {
        log.warn(
            "Could not read downloaded RSS attachment to detect its format, skipping: {} (from"
                + " entry {})",
            candidate.url(),
            entryUrl,
            e);
        events.record(
            IndexingEventCategory.ERROR,
            "Anlage konnte nach dem Herunterladen nicht auf ihr Format geprüft werden",
            candidate.url());
        anyEntryDeferred.set(true);
        return;
      }
      SupportedDocumentFormats.ContentDecision decision =
          SupportedDocumentFormats.decideForFileName(fileName, detectedMimeType);
      if (!decision.supported()) {
        log.info(
            "Skipping RSS attachment with an unsupported format: {} (from entry {}, Content-Type"
                + " {})",
            candidate.url(),
            entryUrl,
            contentType);
        events.record(
            IndexingEventCategory.UNSUPPORTED_FORMAT,
            "Anlagenformat wird nicht unterstützt",
            candidate.url());
        anyEntryDeferred.set(true);
        return;
      }
      if (decision.extensionMismatch()) {
        // Indexed anyway, only reported.
        events.record(
            IndexingEventCategory.FORMAT_MISMATCH,
            "Dateiendung passt nicht zum erkannten Inhalt (erkannt: "
                + decision.detectedExtension()
                + ")",
            candidate.url());
      }

      // Files.probeContentType inside FileProcessingService#processUrlFile probes the physical
      // temp file, which for a GSB attachment carries no extension (".tmp") - renaming it to match
      // the resolved name's extension lets that probe succeed.
      Path indexedFile = withMatchingExtension(downloaded.path(), fileName);

      long size = Files.size(indexedFile);
      FileProcessingResult result =
          fileProcessingService.processUrlFile(
              indexedFile,
              fileName,
              candidate.url(),
              null,
              size,
              targetLibrary,
              DocumentSourceType.RSS_FEED,
              entryUrl);
      if (result == FileProcessingResult.QUOTA_EXCEEDED) {
        // Deferred, not recordSkipped: an attachment was never a discrete unit of the run's own
        // total, so there is nothing to mark skipped - only the feed's ETag persistence to defer
        // so a future run retries it.
        events.record(
            IndexingEventCategory.REJECTED,
            storageQuotaService.quotaExceededMessage(targetLibrary.getId()),
            candidate.url());
        anyEntryDeferred.set(true);
        return;
      }
      // An unchanged attachment (same checksum as an already-indexed document) is deduplicated by
      // processUrlFile itself and returns SKIPPED - must not inflate the document count again.
      if (result == FileProcessingResult.PROCESSED) {
        progress.recordDocumentIndexed();
      }
      log.info("Indexed RSS attachment: {} (from entry {})", candidate.url(), entryUrl);
    } catch (UrlFileDownloader.AttachmentTooLargeException e) {
      log.warn(
          "Skipping RSS attachment exceeding the size limit of {} bytes: {} (from entry {})",
          properties.maxAttachmentSizeBytes(),
          candidate.url(),
          entryUrl);
      events.record(
          IndexingEventCategory.REJECTED,
          "Anlage überschreitet die zulässige Größe",
          candidate.url());
      anyEntryDeferred.set(true);
    } catch (UrlFileDownloader.ForeignHostRedirectException e) {
      log.warn(
          "RSS attachment redirected to a foreign host, skipping: {} (from entry {}, {})",
          candidate.url(),
          entryUrl,
          e.getMessage());
      events.record(IndexingEventCategory.REJECTED, e.userMessage() + " (Anlage)", candidate.url());
      anyEntryDeferred.set(true);
    } catch (TargetAddressValidator.TargetAddressBlockedException e) {
      log.warn(
          "RSS attachment target rejected, skipping: {} (from entry {}, {})",
          candidate.url(),
          entryUrl,
          e.getMessage());
      events.record(IndexingEventCategory.REJECTED, e.getMessage() + " (Anlage)", candidate.url());
      anyEntryDeferred.set(true);
    } catch (IOException | InterruptedException e) {
      log.warn(
          "RSS attachment unreachable, skipping: {} (from entry {}, {})",
          candidate.url(),
          entryUrl,
          e.getMessage());
      events.record(IndexingEventCategory.UNREACHABLE, "Anlage nicht erreichbar", candidate.url());
      anyEntryDeferred.set(true);
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    } catch (Exception e) {
      log.error(
          "Failed to process RSS attachment: {} (from entry {})", candidate.url(), entryUrl, e);
      events.record(
          IndexingEventCategory.ERROR, "Verarbeitung der Anlage fehlgeschlagen", candidate.url());
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
   * Appends an extension derived from {@code contentType} when {@code suggestedFileName} carries no
   * extension at all (the Government Site Builder profile's case) - a no-op for {@link
   * AttachmentProfile#GENERIC} candidates, which always already carry one. Checks {@code
   * AttachmentProfile.fileHasSomeExtension}, not {@link SupportedDocumentFormats#isSupported}: a
   * GENERIC candidate can carry an extension {@link SupportedDocumentFormats} does not recognize
   * (e.g. {@code bescheid.csv}), and must not get a second, content-type-derived extension appended
   * on top. Only a name with no extension whatsoever gets one synthesized here; from here on, only
   * the actually detected content - never this declared, server-asserted {@code contentType} -
   * decides acceptance.
   *
   * <p>Known gap: a GSB attachment (no URL extension) that mislabels a non-text response as {@code
   * Content-Type: text/plain} is still trusted for the text-tolerant acceptance branch of {@link
   * SupportedDocumentFormats#decideForFileName}, since the declared header is the only hint
   * available for an extension-less address.
   */
  private static String resolveFileName(String suggestedFileName, String contentType) {
    if (AttachmentProfile.fileHasSomeExtension(suggestedFileName)) {
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
   * does not already have it. A no-op when the extension already matches, which covers every {@link
   * AttachmentProfile#GENERIC} attachment.
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
      HttpClient httpClient, String feedUrl, Optional<RssFeedState> feedState, String authHeader)
      throws IOException, InterruptedException {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("User-Agent", properties.userAgent());
    // sendFollowingRedirects drops this header the moment a hop leaves the feed's own origin, so
    // a redirect never leaks it to a foreign host.
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
    return AutoindexCrawlerService.sendFollowingRedirects(
        httpClient, feedUrl, Duration.ofSeconds(60), headers, targetAddressValidator);
  }

  private void saveFeedState(
      UUID libraryId, String feedUrl, HttpResponse<InputStream> feedResponse) {
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

  /** An entry's detail page, reduced to its main content's text and attachment candidates. */
  private record DetailPage(String mainText, List<AttachmentCandidate> attachments) {}

  /**
   * Fetches a single entry's detail page and reduces it to its main content's text, together with
   * every attachment the configured {@link AttachmentProfile} finds inside that same content area.
   * {@code nav}/{@code header}/{@code footer}/menu-ish elements are stripped before the configured
   * selector is applied, so boilerplate inside the matched main element does not survive either and
   * is never considered for attachments.
   *
   * <p>{@link #httpClientForTarget} picks {@code secureClient}/{@code insecureClient} for {@code
   * entryUrl} once, before any redirect is followed - {@link #sendDetailPageRequest} refuses every
   * hop that would leave {@code entryUrl}'s own origin, so the origin never changes mid-chain.
   */
  private DetailPage fetchDetailPage(
      HttpClient secureClient,
      HttpClient insecureClient,
      String feedUrl,
      String entryUrl,
      String authHeader)
      throws IOException, InterruptedException {
    HttpClient httpClient = httpClientForTarget(secureClient, insecureClient, feedUrl, entryUrl);
    HttpResponse<InputStream> response = sendDetailPageRequest(httpClient, entryUrl, authHeader);

    // Every path below - the three early rejections and the ordinary 200 - must close the
    // response body, hence try-with-resources around the whole evaluation.
    try (InputStream body = response.body()) {
      if (response.statusCode() == 403 || response.statusCode() == 429) {
        throw new RejectedByRemoteException(
            "HTTP " + response.statusCode(),
            "Vom Quellserver abgewiesen (HTTP " + response.statusCode() + ")");
      }
      if (isForeignHostRedirect(entryUrl, response.uri())) {
        throw new RejectedByRemoteException(
            "redirected to a foreign host: " + response.uri(),
            AutoindexCrawlerService.redirectRejectionMessage(
                AutoindexCrawlerService.RedirectRejectionReason.FOREIGN_HOST, response.uri()));
      }
      if (response.statusCode() != 200) {
        throw new IOException("HTTP " + response.statusCode() + " for URL: " + entryUrl);
      }

      String contentType = response.headers().firstValue("Content-Type").orElse(null);
      if (!isHtmlContentType(contentType)) {
        // A <link> pointing straight at a PDF (or anything else that is not HTML) must never be
        // pushed through Jsoup - attachments are handled separately.
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

      // The server's declared charset wins when present; otherwise Jsoup.parse(InputStream, ...)
      // itself detects the charset from a BOM or a <meta> tag and falls back to UTF-8 - never a
      // hardcoded StandardCharsets.UTF_8, which silently mangles e.g. ISO-8859-1 into U+FFFD.
      org.jsoup.nodes.Document htmlDoc =
          Jsoup.parse(new ByteArrayInputStream(pageBytes), charsetNameFrom(contentType), entryUrl);
      // nav/header/footer/menu-ish elements never survive into the index, regardless of whether
      // they sit inside or outside the matched main element below.
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

  /**
   * Sends the detail-page request for {@code entryUrl}, manually following up to {@link
   * AutoindexCrawlerService#MAX_REDIRECTS} same-origin redirects - {@code httpClient} (built with
   * {@code Redirect.NEVER}) never follows one on its own. A redirect off origin (different
   * host/scheme, or a protocol downgrade - {@link AutoindexCrawlerService#sameOrigin}) is rejected
   * right here with a {@link RejectedByRemoteException}, before the foreign target is contacted.
   *
   * <p>{@code authHeader} is sent on every hop this loop reaches - a foreign host is always
   * rejected before its request is built, so the header is never resent outside {@code entryUrl}'s
   * own origin.
   */
  private HttpResponse<InputStream> sendDetailPageRequest(
      HttpClient httpClient, String entryUrl, String authHeader)
      throws IOException, InterruptedException {
    URI currentUri = URI.create(entryUrl);
    for (int hop = 0; ; hop++) {
      targetAddressValidator.validate(currentUri);
      HttpRequest.Builder requestBuilder =
          HttpRequest.newBuilder()
              .uri(currentUri)
              .timeout(Duration.ofSeconds(30))
              .header("User-Agent", properties.userAgent())
              .GET();
      if (authHeader != null) {
        requestBuilder.header("Authorization", authHeader);
      }
      HttpResponse<InputStream> response =
          httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

      if (!AutoindexCrawlerService.isRedirectStatus(response.statusCode())
          || hop >= AutoindexCrawlerService.MAX_REDIRECTS) {
        return response;
      }
      Optional<String> location = response.headers().firstValue("Location");
      if (location.isEmpty()) {
        return response;
      }
      URI redirectUri = currentUri.resolve(location.get());
      closeQuietly(response.body());
      // A protocol downgrade is refused outright - see AutoindexCrawlerService.isSchemeDowngrade.
      if (AutoindexCrawlerService.isSchemeDowngrade(currentUri, redirectUri)) {
        throw new RejectedByRemoteException(
            "refusing a protocol downgrade redirect (https to http): " + redirectUri,
            AutoindexCrawlerService.redirectRejectionMessage(
                AutoindexCrawlerService.RedirectRejectionReason.PROTOCOL_DOWNGRADE, redirectUri));
      }
      if (isForeignHostRedirect(currentUri.toString(), redirectUri)) {
        throw new RejectedByRemoteException(
            "redirected to a foreign host: " + redirectUri,
            AutoindexCrawlerService.redirectRejectionMessage(
                AutoindexCrawlerService.RedirectRejectionReason.FOREIGN_HOST, redirectUri));
      }
      currentUri = redirectUri;
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
   * "detect from the document itself".
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
   * Whether {@code finalUri} is a different origin (scheme, host and normalized port) than {@code
   * originalUrl} - the signature of a bot-protection challenge page. An unparsable host on either
   * side, or an unparsable {@code originalUrl}, is always treated as foreign, never trusted with
   * the feed's own credentials. Delegates to {@link
   * AutoindexCrawlerService#isRedirectOriginTrusted} rather than {@code sameOrigin} directly, so a
   * same-host {@code http}→{@code https} upgrade is not counted as foreign; {@link
   * #sendDetailPageRequest} refuses the opposite (downgrade) direction unconditionally.
   */
  private boolean isForeignHostRedirect(String originalUrl, URI finalUri) {
    try {
      URI originalUri = new URI(originalUrl);
      return !AutoindexCrawlerService.isRedirectOriginTrusted(originalUri, finalUri);
    } catch (URISyntaxException e) {
      return true;
    }
  }

  /**
   * Restricts {@code authHeader} to a request whose target shares the feed's own origin ({@link
   * AutoindexCrawlerService#sameOrigin}). Neither {@code sendDetailPageRequest} nor {@code
   * UrlFileDownloader#downloadBounded}'s own foreign-host checks protect against the starting
   * address of a request: both only compare a redirect hop against the previous one, never against
   * the feed itself. An entry's own {@code <link>} or an attachment URL is content the feed
   * operator controls, so a request outside the feed's origin must never carry the feed's own
   * credentials - the entry/attachment is still processed, only the header is withheld. An
   * unparseable {@code targetUrl} is treated as foreign.
   */
  private static String authHeaderForTarget(String authHeader, String feedUrl, String targetUrl) {
    if (authHeader == null) {
      return null;
    }
    return isSameOriginAsFeed(feedUrl, targetUrl) ? authHeader : null;
  }

  /**
   * Picks {@code insecureClient} for a request whose target shares the feed's own origin, {@code
   * secureClient} for anything else. {@code sourceInsecureSsl} is a property of the library's own
   * configured source, not a blanket "skip certificate validation for whatever this feed points at"
   * - mirrors {@link #authHeaderForTarget}'s credentials reasoning. An unparseable {@code
   * targetUrl} is treated as foreign (the secure client).
   */
  private static HttpClient httpClientForTarget(
      HttpClient secureClient, HttpClient insecureClient, String feedUrl, String targetUrl) {
    return isSameOriginAsFeed(feedUrl, targetUrl) ? insecureClient : secureClient;
  }

  private static boolean isSameOriginAsFeed(String feedUrl, String targetUrl) {
    try {
      return AutoindexCrawlerService.sameOrigin(URI.create(feedUrl), URI.create(targetUrl));
    } catch (IllegalArgumentException e) {
      // An unparseable target URL is never trusted as same-origin.
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
   * Whether {@code url} is a syntactically valid, resolvable {@link URI} with a parseable host.
   * {@code isHttpOrHttps} only checks the scheme prefix, so a malformed link would otherwise make
   * {@code URI.create(url)} throw {@link IllegalArgumentException} inside {@link #fetchDetailPage}.
   * {@code getHost() != null} is checked too: {@code URI.create} accepts a link whose host it
   * cannot parse (e.g. a raw, non-punycode IDN) without throwing, and only the later {@code
   * HttpRequest} builder inside {@link #sendDetailPageRequest} would reject it.
   */
  private static boolean isValidUri(String url) {
    try {
      return URI.create(url).getHost() != null;
    } catch (IllegalArgumentException e) {
      return false;
    }
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
    // Mirrors the check FileProcessingService#processRssEntry makes (library changed -> not
    // unchanged): without it, moving the target library would never take effect for an entry
    // whose pubDate is otherwise unchanged.
    return existing.isPresent()
        && publishedAt.get().toString().equals(existing.get().getLastModifiedRemote())
        && existing.get().getStatus() == DocumentStatus.INDEXED
        && targetLibrary.getId().equals(existing.get().getLibraryId());
  }

  /**
   * Reads at most {@code maxBytes} from {@code in}, throwing {@link FeedTooLargeException} the
   * moment a further byte would exceed the limit - enforced while streaming, not after the full
   * response has already been downloaded.
   */
  private static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
    byte[] probe = in.readNBytes(Math.toIntExact(Math.min(maxBytes + 1, Integer.MAX_VALUE)));
    if (probe.length > maxBytes) {
      throw new FeedTooLargeException();
    }
    return probe;
  }

  /**
   * Closes a response body on a path that never reads it - {@code close()} on the {@code
   * InputStream} is what actually releases the underlying connection.
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
   * Thrown when the remote end itself declined to hand over a detail page (403/429, a redirect to a
   * foreign host, or a refused protocol downgrade) - kept distinct from an ordinary {@link
   * IOException} so the caller can log and count it separately from a processing failure. {@link
   * #userMessage()} is a German, cause-specific, sanitized run-log text, distinct from this
   * exception's own message, which stays the unsanitized, developer-facing detail for the log only.
   */
  private static final class RejectedByRemoteException extends RuntimeException {
    private final String userMessage;

    RejectedByRemoteException(String logMessage, String userMessage) {
      super(logMessage);
      this.userMessage = userMessage;
    }

    String userMessage() {
      return userMessage;
    }
  }

  /**
   * Thrown when a detail page's {@code Content-Type} is not HTML - e.g. a {@code <link>} pointing
   * straight at a PDF. Kept distinct from {@link RejectedByRemoteException}: the remote end
   * answered normally here, it just did not hand over something this executor can extract text
   * from.
   */
  private static final class UnsupportedContentTypeException extends RuntimeException {
    UnsupportedContentTypeException(String actualContentType) {
      super(actualContentType);
    }
  }
}
