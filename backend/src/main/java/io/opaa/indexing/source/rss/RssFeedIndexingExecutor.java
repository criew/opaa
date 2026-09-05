package io.opaa.indexing.source.rss;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentIngest;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.IndexingRunEventRecorder;
import io.opaa.indexing.IndexingRunProgress;
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.indexing.source.IndexingRun;
import io.opaa.indexing.source.IndexingRunFailedException;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.IndexingSourceType;
import io.opaa.indexing.source.ListingOutcome;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.indexing.source.attachment.AttachmentCandidate;
import io.opaa.indexing.source.attachment.AttachmentDownloadLimits;
import io.opaa.indexing.source.attachment.AttachmentIndexer;
import io.opaa.indexing.source.attachment.AttachmentSource;
import io.opaa.indexing.source.web.DetailPageExtractor;
import io.opaa.library.KnowledgeLibrary;
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
import org.springframework.scheduling.annotation.Async;

/**
 * Executes indexing runs for {@link IndexingSourceType#RSS_FEED} (ADR-0017): fetches the feed,
 * resolves every entry's detail page and hands the page's main text - not the whole page - into
 * {@link FileProcessingService#ingest}. Transport, page reduction and attachments belong to {@link
 * FeedFetcher}, {@link DetailPageExtractor} and {@link AttachmentIndexer}; this class keeps the
 * orchestration and the per-run state ({@link RssFeedRunContext}).
 *
 * <p>Change detection is three-staged: a conditional {@code GET} on the feed, each entry's stored
 * {@code pubDate}, then the SHA-256 checksum. <b>No deletion by absence</b> (decision 5): a feed's
 * window is a property of the feed. A rejected or unreachable entry never aborts the run, and an
 * attachment failure only marks the run as having deferred something.
 */
public class RssFeedIndexingExecutor implements SourceIndexingExecutor {

  private static final Logger log = LoggerFactory.getLogger(RssFeedIndexingExecutor.class);

  private final FileProcessingService fileProcessingService;
  private final DocumentRepository documentRepository;
  private final IndexingProperties.Rss properties;
  private final FeedFetcher feedFetcher;
  private final DetailPageExtractor detailPageExtractor;
  private final AttachmentIndexer attachmentIndexer;
  private final AttachmentDownloadLimits attachmentLimits;
  private final IndexingRunTemplate runTemplate;

  public RssFeedIndexingExecutor(
      RssFeedParser feedParser,
      FileProcessingService fileProcessingService,
      DocumentRepository documentRepository,
      RssFeedStateRepository feedStateRepository,
      AttachmentIndexer attachmentIndexer,
      IndexingProperties properties,
      TargetAddressValidator targetAddressValidator,
      IndexingRunTemplate runTemplate) {
    this.fileProcessingService = fileProcessingService;
    this.documentRepository = documentRepository;
    this.properties = properties.rss();
    this.feedFetcher =
        new FeedFetcher(targetAddressValidator, feedStateRepository, feedParser, this.properties);
    this.detailPageExtractor = new DetailPageExtractor(targetAddressValidator, this.properties);
    this.attachmentIndexer = attachmentIndexer;
    this.attachmentLimits =
        new AttachmentDownloadLimits(
            this.properties.maxAttachmentsPerEntry(),
            this.properties.maxAttachmentSizeBytes(),
            this.properties.requestDelayMs(),
            this.properties.userAgent());
    this.runTemplate = runTemplate;
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
    runTemplate.run(jobId, targetLibrary, runMode, this, this::indexFeed);
  }

  private ListingOutcome indexFeed(IndexingRun run) throws IOException, InterruptedException {
    KnowledgeLibrary targetLibrary = run.library();
    // ADR-0018: the feed's address is the library's own sourceUrl, not a per-request field.
    String feedUrl = targetLibrary.getSourceUrl();
    ProxyAndCredentials config;
    try {
      config =
          ProxyAndCredentials.parse(
              targetLibrary.getSourceProxy(), targetLibrary.getSourceCredentials());
    } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
      throw new IndexingRunFailedException(e.getMessage());
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
            ? SourceHttpClientFactory.buildHttpClient(config.proxyHost(), config.proxyPort(), true)
            : secureClient;

    Optional<FeedFetcher.LoadedFeed> loaded =
        feedFetcher.fetchAndParse(insecureClient, targetLibrary.getId(), feedUrl, authHeader);
    if (loaded.isEmpty()) {
      run.progress().setTotal(0);
      return ListingOutcome.partial();
    }
    List<RssFeedEntry> entries = loaded.get().entries();
    run.progress().setTotal(entries.size());
    run.progress().report();

    var ctx =
        new RssFeedRunContext(
            secureClient,
            insecureClient,
            targetLibrary,
            authHeader,
            feedUrl,
            run.progress(),
            run.events(),
            new AtomicBoolean(loaded.get().truncated()));
    for (RssFeedEntry entry : entries) {
      processEntry(run, ctx, entry);
      run.progress().report();
    }

    // An ETag/Last-Modified saved after a run that deferred entries would let a future 304
    // permanently hide those entries - the conditional-GET state only advances once a run has
    // accounted for every entry it saw.
    if (!ctx.anyEntryDeferred().get() && run.progress().failedCount() == 0) {
      feedFetcher.saveState(targetLibrary.getId(), feedUrl, loaded.get().feedResponse());
    } else {
      log.info(
          "Not persisting RSS feed state for {} - this run deferred or failed at least one"
              + " entry, so a future 304 must not suppress it",
          feedUrl);
    }
    return ListingOutcome.partial();
  }

  private void processEntry(IndexingRun run, RssFeedRunContext ctx, RssFeedEntry entry) {
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
    if (run.isUnchanged(entryUrl, publishedAt.map(Instant::toString).orElse(null))) {
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
      // The entry body never was a file: already-extracted text, its headline as the declared
      // title and its publication instant as the document's own date (ADR-0017, decision 2).
      FileProcessingResult result =
          fileProcessingService.ingest(
              DocumentIngest.text(ctx.targetLibrary(), entryUrl, detailPage.mainText())
                  .sourceType(DocumentSourceType.RSS_FEED)
                  .title(entry.title())
                  .changeMarker(publishedAt.map(Instant::toString).orElse(null))
                  .documentDate(DocumentProperties.instantToLocalDate(publishedAt.orElse(null)))
                  .build(),
              null);
      // A rejected or failed entry's attachments are deliberately not indexed.
      if (run.recordOutcome(result, entryUrl)) {
        log.info("Indexed RSS entry: {}", entryUrl);
        indexAttachments(ctx, detailPage.attachments(), entryUrl);
      }
    } catch (Exception | Error e) {
      run.recordFailure(entryUrl, e);
    }
  }

  /**
   * Handles an entry whose {@code pubDate} is unchanged: since it never changes again, {@link
   * #processEntry} would otherwise never re-fetch the detail page attachments are found on. Fetches
   * that page for attachments alone - not the entry's text - and only while no attachment document
   * exists for it in this run's own library, so an entry that already has them stays cheap.
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
   * Converts {@code candidates} into {@link AttachmentSource.Download} jobs - client and {@code
   * Authorization} decided per candidate by {@code ctx.httpClientFor}/{@code ctx.authHeaderFor} -
   * and hands them to {@link AttachmentIndexer#indexAll} under {@code entryUrl}'s own document row
   * as {@code parentDocumentId}, which every call site has already ensured exists.
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
}
