package io.opaa.indexing.source.rss;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.DocumentService;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.IndexingRunEventRecorder;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.IndexingRunProgress;
import io.opaa.indexing.source.IndexingSourceType;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.indexing.source.attachment.AttachmentCandidate;
import io.opaa.indexing.source.attachment.AttachmentDownloadLimits;
import io.opaa.indexing.source.attachment.AttachmentIndexer;
import io.opaa.indexing.source.attachment.AttachmentSource;
import io.opaa.indexing.source.web.DetailPageExtractor;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.ProxyAndCredentials;
import io.opaa.sourceaccess.RequestPoliteness;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;

/**
 * Executes indexing runs for {@link IndexingSourceType#RSS_FEED} (ADR-0017): fetches an RSS 2.0
 * feed, resolves every entry's detail page and hands the page's main text - not the whole page -
 * into the shared processing chain via {@link FileProcessingService#processRssEntry}.
 *
 * <p><b>Split into collaborators.</b> {@link FeedFetcher} owns the feed's own transport, {@link
 * DetailPageExtractor} owns fetching and reducing a single entry's detail page, {@link
 * AttachmentIndexer} owns downloading and indexing its attachments. This class is left with the
 * run's own orchestration: change detection, ordering, error-to-event translation and the per-run
 * state ({@link RssFeedRunContext}) shared across all three - see each collaborator's own Javadoc
 * for the invariants it enforces (bounded reads, SSRF/redirect policy, credential/certificate
 * scoping to the feed's own origin).
 *
 * <p><b>Two-stage change detection.</b> The feed itself is fetched with a conditional {@code GET}
 * (ETag/{@code If-Modified-Since}, tracked per library and feed URL in {@link RssFeedState}) - an
 * unchanged feed ends the run after a single {@code 304} response. Every entry is then checked
 * against its stored {@code pubDate} before its detail page is requested; the SHA-256 checksum
 * inside {@link FileProcessingService#processRssEntry} is the final, content-based layer once a
 * page is fetched. No deletion by absence (ADR-0017, decision 5): an entry that has scrolled out of
 * the feed's window is not touched here - re-confirmed by #886, which added deletion-by-absence for
 * {@code FILESYSTEM}/{@code HTTP_DIRECTORY} but deliberately excludes RSS: a feed's window is a
 * property of the feed, not of whether the entry's own source still exists, so "missing from this
 * run's entries" is not evidence an entry is gone.
 *
 * <p>Entry- and byte-size limits, a link scheme check ({@code http}/{@code https} only), a minimum
 * delay between detail-page requests and a configurable {@code User-Agent} come from {@link
 * IndexingProperties.Rss}. A rejected, oversized or unreachable entry never aborts the run; it is
 * skipped, counted and logged.
 *
 * <p><b>Attachments.</b> Once an entry's detail page has yielded its main text, {@link
 * AttachmentIndexer} downloads and indexes every attachment found in that same content area. An
 * attachment failure never affects the entry's own outcome, but marks the run as having deferred
 * something. An entry whose {@code pubDate} is unchanged still gets its detail page fetched once
 * for attachments alone when it has none yet (see {@link #processUnchangedEntry}) - otherwise it
 * would never receive attachments discovered after it was first indexed.
 */
public class RssFeedIndexingExecutor implements SourceIndexingExecutor {

  private static final Logger log = LoggerFactory.getLogger(RssFeedIndexingExecutor.class);

  private final FileProcessingService fileProcessingService;
  private final IndexingJobService indexingJobService;
  private final DocumentRepository documentRepository;
  private final IndexingProperties.Rss properties;
  private final IndexingRunEventRepository indexingRunEventRepository;
  private final LibraryStorageQuotaService storageQuotaService;
  private final FeedFetcher feedFetcher;
  private final DetailPageExtractor detailPageExtractor;
  private final AttachmentIndexer attachmentIndexer;
  private final AttachmentDownloadLimits attachmentLimits;

  public RssFeedIndexingExecutor(
      RssFeedParser feedParser,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      DocumentRepository documentRepository,
      RssFeedStateRepository feedStateRepository,
      BoundedDownloader attachmentDownloader,
      IndexingProperties properties,
      IndexingRunEventRepository indexingRunEventRepository,
      TargetAddressValidator targetAddressValidator,
      LibraryStorageQuotaService storageQuotaService) {
    this.fileProcessingService = fileProcessingService;
    this.indexingJobService = indexingJobService;
    this.documentRepository = documentRepository;
    this.properties = properties.rss();
    this.indexingRunEventRepository = indexingRunEventRepository;
    this.storageQuotaService = storageQuotaService;
    this.feedFetcher =
        new FeedFetcher(targetAddressValidator, feedStateRepository, feedParser, this.properties);
    this.detailPageExtractor = new DetailPageExtractor(targetAddressValidator, this.properties);
    this.attachmentIndexer =
        new AttachmentIndexer(
            attachmentDownloader, fileProcessingService, storageQuotaService, documentRepository);
    this.attachmentLimits =
        new AttachmentDownloadLimits(
            this.properties.maxAttachmentsPerEntry(),
            this.properties.maxAttachmentSizeBytes(),
            this.properties.requestDelayMs(),
            this.properties.userAgent());
  }

  @Override
  public IndexingSourceType sourceType() {
    return IndexingSourceType.RSS_FEED;
  }

  @Override
  public Map<IndexingRunMode, VanishedDocumentPolicy> runModes() {
    // ADR-0023, Entscheidung 4: one mode only, "ergänzend" - never deletes by absence.
    return Map.of(IndexingRunMode.INCREMENTAL, VanishedDocumentPolicy.KEEP_ON_ABSENCE);
  }

  @Override
  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode) {
    var progress = new IndexingRunProgress(indexingJobService, jobId);
    var events =
        new IndexingRunEventRecorder(indexingRunEventRepository, indexingJobService, jobId);
    if (!runModes().containsKey(runMode)) {
      progress.fail("Betriebsart " + runMode + " wird für diesen Quellentyp nicht unterstützt");
      return;
    }
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
          SourceHttpClientFactory.buildAuthHeader(config.username(), config.password());

      // secureClient always validates certificates normally; insecureClient relaxes validation
      // only when the library asks for it and is used exclusively for same-origin requests - see
      // RssFeedRunContext#httpClientFor's own Javadoc.
      HttpClient secureClient =
          SourceHttpClientFactory.buildHttpClient(config.proxyHost(), config.proxyPort(), false);
      HttpClient insecureClient =
          targetLibrary.isSourceInsecureSsl()
              ? SourceHttpClientFactory.buildHttpClient(
                  config.proxyHost(), config.proxyPort(), true)
              : secureClient;

      Optional<FeedFetcher.LoadedFeed> loaded =
          feedFetcher.fetchAndParse(
              insecureClient, targetLibrary.getId(), feedUrl, authHeader, progress);
      if (loaded.isEmpty()) {
        return;
      }
      List<RssFeedEntry> entries = loaded.get().entries();
      progress.setTotal(entries.size());
      progress.report();

      var ctx =
          new RssFeedRunContext(
              secureClient,
              insecureClient,
              targetLibrary,
              authHeader,
              feedUrl,
              progress,
              events,
              new AtomicBoolean(loaded.get().truncated()));
      for (RssFeedEntry entry : entries) {
        processEntry(ctx, entry);
        progress.report();
      }

      // An ETag/Last-Modified saved after a run that deferred entries would let a future 304
      // permanently hide those entries - the conditional-GET state only advances once a run has
      // accounted for every entry it saw.
      if (!ctx.anyEntryDeferred().get() && progress.failedCount() == 0) {
        feedFetcher.saveState(targetLibrary.getId(), feedUrl, loaded.get().feedResponse());
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
      // violation - the target library was deleted between this run starting and saveState's
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

  private void processEntry(RssFeedRunContext ctx, RssFeedEntry entry) {
    String entryUrl = entry.link();
    IndexingRunProgress progress = ctx.progress();
    IndexingRunEventRecorder events = ctx.events();

    if (!isHttpOrHttps(entryUrl)) {
      log.warn(
          "Skipping RSS entry with a non-http(s) link (rejected by scheme check): {}", entryUrl);
      events.record(
          IndexingEventCategory.REJECTED,
          "Verknüpfung mit nicht unterstütztem Schema abgelehnt",
          entryUrl);
      progress.recordSkipped();
      ctx.anyEntryDeferred().set(true);
      return;
    }

    // An http(s)-prefixed link can still be syntactically invalid; URI.create(entryUrl) inside
    // DetailPageExtractor#fetch would then throw IllegalArgumentException uncaught, ending the
    // whole run instead of just this entry.
    if (!isValidUri(entryUrl)) {
      log.warn("Skipping RSS entry with a syntactically invalid link: {}", entryUrl);
      events.record(
          IndexingEventCategory.REJECTED, "Verknüpfung mit ungültiger URL abgelehnt", entryUrl);
      progress.recordSkipped();
      ctx.anyEntryDeferred().set(true);
      return;
    }

    Optional<Instant> publishedAt = entry.publishedAt();
    if (isUnchanged(entryUrl, publishedAt, ctx.targetLibrary())) {
      processUnchangedEntry(ctx, entryUrl);
      return;
    }

    RequestPoliteness.delayBeforeRequest(properties.requestDelayMs());

    Optional<DetailPageExtractor.DetailPage> fetched =
        fetchDetailPageForEntry(ctx, entryUrl, false);
    if (fetched.isEmpty()) {
      return;
    }
    DetailPageExtractor.DetailPage detailPage = fetched.get();

    if (detailPage.mainText() == null || detailPage.mainText().isBlank()) {
      log.warn("RSS detail page yielded no extractable text, skipping: {}", entryUrl);
      events.record(IndexingEventCategory.UNSUPPORTED_FORMAT, "Kein Inhalt extrahierbar", entryUrl);
      progress.recordSkipped();
      ctx.anyEntryDeferred().set(true);
      return;
    }

    try {
      FileProcessingResult result =
          fileProcessingService.processRssEntry(
              detailPage.mainText(),
              entry.title(),
              entryUrl,
              publishedAt.map(Instant::toString).orElse(null),
              ctx.targetLibrary());
      if (result == FileProcessingResult.QUOTA_EXCEEDED) {
        // See AsyncIndexingExecutor's own handling of this outcome.
        events.record(
            IndexingEventCategory.REJECTED,
            storageQuotaService.quotaExceededMessage(ctx.targetLibrary().getId()),
            entryUrl);
        progress.recordSkipped();
      } else if (result == FileProcessingResult.NO_EXTRACTABLE_TEXT) {
        // See AsyncIndexingExecutor's own handling of this outcome. Reachable on this path since
        // #1056: the entry's own document was rejected and marked FAILED, so it is reported as
        // rejected rather than counted as processed - and its attachments are deliberately not
        // indexed, mirroring every other rejected entry.
        events.record(
            IndexingEventCategory.REJECTED, DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE, entryUrl);
        progress.recordSkipped();
      } else if (result == FileProcessingResult.FAILED) {
        // See AsyncIndexingExecutor's own handling of this outcome. Its attachments are
        // deliberately not indexed, mirroring every other rejected entry.
        events.record(IndexingEventCategory.ERROR, "Verarbeitung fehlgeschlagen", entryUrl);
        progress.recordFailed();
      } else if (result == FileProcessingResult.SKIPPED) {
        progress.recordSkipped();
      } else {
        progress.recordProcessed();
        log.info("Indexed RSS entry: {}", entryUrl);
        indexAttachments(ctx, detailPage.attachments(), entryUrl);
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
   * reprocessed) only when no attachment document exists yet for it in this run's own library
   * ({@link DocumentRepository#existsBySourceEntryUrlAndLibraryId}); an entry that already has its
   * attachments there stays as cheap as before. Scoped to {@code ctx.targetLibrary()} (#877) - a
   * different library's attachments for the same entry URL must not suppress this library's own
   * backfill.
   */
  private void processUnchangedEntry(RssFeedRunContext ctx, String entryUrl) {
    ctx.progress().recordSkipped();
    if (documentRepository.existsBySourceEntryUrlAndLibraryId(
        entryUrl, ctx.targetLibrary().getId())) {
      log.info("Skipping unchanged RSS entry (unchanged pubDate): {}", entryUrl);
      return;
    }

    log.info(
        "RSS entry unchanged but has no attachment documents yet, fetching its detail page to"
            + " backfill attachments only: {}",
        entryUrl);
    RequestPoliteness.delayBeforeRequest(properties.requestDelayMs());
    Optional<DetailPageExtractor.DetailPage> fetched = fetchDetailPageForEntry(ctx, entryUrl, true);
    fetched.ifPresent(detailPage -> indexAttachments(ctx, detailPage.attachments(), entryUrl));
  }

  /**
   * Converts {@code candidates} into {@link AttachmentSource.Download} jobs (the {@link HttpClient}
   * and {@code Authorization} header a download uses are decided per candidate by {@code
   * ctx.httpClientFor}/{@code ctx.authHeaderFor}, exactly as before #1182's generalization) and
   * hands them to {@link AttachmentIndexer#indexAll}, looking up {@code entryUrl}'s own document
   * row as {@code parentDocumentId} - present at this point on every call site, since both call it
   * only once its own entry document is known to exist.
   */
  private void indexAttachments(
      RssFeedRunContext ctx, List<AttachmentCandidate> candidates, String entryUrl) {
    Optional<Document> entryDocument =
        documentRepository.findByLibraryIdAndFilePath(ctx.targetLibrary().getId(), entryUrl);
    if (entryDocument.isEmpty()) {
      log.warn(
          "RSS entry document vanished before its attachments could be indexed, skipping: {}",
          entryUrl);
      return;
    }
    List<AttachmentSource> sources =
        candidates.stream()
            .map(
                candidate ->
                    (AttachmentSource)
                        new AttachmentSource.Download(
                            candidate.url(),
                            candidate.suggestedFileName(),
                            ctx.httpClientFor(candidate.url()),
                            ctx.authHeaderFor(candidate.url())))
            .toList();
    attachmentIndexer.indexAll(
        ctx,
        sources,
        entryDocument.get().getId(),
        entryUrl,
        DocumentSourceType.RSS_FEED,
        attachmentLimits);
  }

  /**
   * Fetches {@code entryUrl}'s detail page, translating every failure into a run event and {@link
   * Optional#empty()} - shared by {@link #processEntry} and {@link #processUnchangedEntry}, whose
   * five exception branches differ only in wording ({@code backfill} selects the phrasing used when
   * re-fetching an unchanged entry's page for attachments alone) and in whether the entry itself
   * still needs {@code recordSkipped()} - already called before backfilling.
   */
  private Optional<DetailPageExtractor.DetailPage> fetchDetailPageForEntry(
      RssFeedRunContext ctx, String entryUrl, boolean backfill) {
    String suffix = backfill ? " (beim Nachladen von Anlagen)" : "";
    try {
      return Optional.of(
          detailPageExtractor.fetch(
              ctx.httpClientFor(entryUrl), entryUrl, ctx.authHeaderFor(entryUrl)));
    } catch (DetailPageExtractor.RejectedByRemoteException e) {
      // ADR-0017: a 403/429/redirect to a foreign host is declined, not a processing failure - the
      // German event message is e.userMessage(), never e.getMessage() (can carry the raw target).
      log.warn(
          backfill
              ? "Could not fetch RSS detail page to backfill attachments, will retry on a future"
                  + " run: {} ({})"
              : "RSS detail page rejected by remote host, skipping: {} ({})",
          entryUrl,
          e.getMessage());
      recordDetailPageFailure(
          ctx, entryUrl, IndexingEventCategory.REJECTED, e.userMessage() + suffix, !backfill);
    } catch (TargetAddressValidator.TargetAddressBlockedException e) {
      log.warn(
          backfill
              ? "Could not fetch RSS detail page to backfill attachments, its target was"
                  + " rejected, will retry on a future run: {} ({})"
              : "RSS detail page target rejected, skipping: {} ({})",
          entryUrl,
          e.getMessage());
      recordDetailPageFailure(
          ctx, entryUrl, IndexingEventCategory.REJECTED, e.getMessage() + suffix, !backfill);
    } catch (DetailPageExtractor.UnsupportedContentTypeException e) {
      // A <link> pointing straight at a PDF (or any non-HTML content) is not pushed through Jsoup.
      log.warn(
          backfill
              ? "Could not fetch RSS detail page to backfill attachments, will retry on a future"
                  + " run: {} ({})"
              : "Skipping RSS detail page with an unsupported content type, skipping: {} ({})",
          entryUrl,
          e.getMessage());
      recordDetailPageFailure(
          ctx,
          entryUrl,
          IndexingEventCategory.UNSUPPORTED_FORMAT,
          backfill
              ? "Inhaltstyp der Detailseite wird nicht unterstützt (Anlagen konnten nicht"
                  + " nachgeladen werden)"
              : "Inhaltstyp der Detailseite wird nicht unterstützt",
          !backfill);
    } catch (IOException | InterruptedException e) {
      log.warn(
          backfill
              ? "RSS detail page unreachable while backfilling attachments, will retry on a"
                  + " future run: {} ({})"
              : "RSS detail page unreachable, skipping: {} ({})",
          entryUrl,
          e.getMessage());
      recordDetailPageFailure(
          ctx,
          entryUrl,
          IndexingEventCategory.UNREACHABLE,
          backfill
              ? "Detailseite beim Nachladen von Anlagen nicht erreichbar"
              : "Detailseite nicht erreichbar",
          !backfill);
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    } catch (IllegalArgumentException e) {
      // entryUrl already passed isValidUri, but a redirect hop's own Location header can still
      // make currentUri.resolve(location) throw here.
      log.warn(
          backfill
              ? "Could not fetch RSS detail page to backfill attachments, its redirect could not"
                  + " be resolved, will retry on a future run: {} ({})"
              : "Skipping RSS entry whose detail-page redirect could not be resolved: {} ({})",
          entryUrl,
          e.getMessage());
      recordDetailPageFailure(
          ctx,
          entryUrl,
          IndexingEventCategory.REJECTED,
          backfill
              ? "Weiterleitung der Detailseite beim Nachladen von Anlagen ungültig"
              : "Weiterleitung der Detailseite ungültig",
          !backfill);
    }
    return Optional.empty();
  }

  private void recordDetailPageFailure(
      RssFeedRunContext ctx,
      String entryUrl,
      IndexingEventCategory category,
      String message,
      boolean recordSkipped) {
    ctx.events().record(category, message, entryUrl);
    if (recordSkipped) {
      ctx.progress().recordSkipped();
    }
    ctx.anyEntryDeferred().set(true);
  }

  private static boolean isHttpOrHttps(String url) {
    if (url == null) {
      return false;
    }
    String lowerCased = url.strip().toLowerCase(Locale.ROOT);
    return lowerCased.startsWith("http://") || lowerCased.startsWith("https://");
  }

  /**
   * Whether {@code url} is a syntactically valid, resolvable {@link URI} with a parseable host -
   * {@code isHttpOrHttps} only checks the scheme prefix, so a malformed link would otherwise make
   * {@code URI.create(url)} throw {@link IllegalArgumentException} inside {@link
   * DetailPageExtractor#fetch}.
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
   * page is ever requested (ADR-0017) - mirrors {@code UrlIndexingExecutor#isUnchanged}. A missing
   * {@code pubDate} is treated as "changed" - the SHA-256 checksum inside {@link
   * FileProcessingService#processRssEntry} becomes the deciding change signal instead. The lookup
   * is scoped to {@code targetLibrary} (#877): the same entry URL indexed into a different library
   * is an independent document.
   */
  private boolean isUnchanged(
      String entryUrl, Optional<Instant> publishedAt, KnowledgeLibrary targetLibrary) {
    if (publishedAt.isEmpty()) {
      return false;
    }
    Optional<Document> existing =
        documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), entryUrl);
    return existing.isPresent()
        && publishedAt.get().toString().equals(existing.get().getLastModifiedRemote())
        && existing.get().getStatus() == DocumentStatus.INDEXED;
  }
}
