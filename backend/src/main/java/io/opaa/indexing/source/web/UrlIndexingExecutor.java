package io.opaa.indexing.source.web;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentIngest;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.SupportedDocumentFormats;
import io.opaa.indexing.source.IndexingRun;
import io.opaa.indexing.source.IndexingRunFailedException;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.IndexingSourceType;
import io.opaa.indexing.source.ListingOutcome;
import io.opaa.indexing.source.ReconcilingAttachmentAccess;
import io.opaa.indexing.source.SourceFolderMirror;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryFolderService;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.ProxyAndCredentials;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

/**
 * Executes indexing runs for {@link IndexingSourceType#HTTP_DIRECTORY} via Apache mod_autoindex
 * crawling (ADR-0017). The crawled directory structure is mirrored into {@code library_folders}
 * (ADR-0020) through the same {@link SourceFolderMirror} the FILESYSTEM executor uses.
 *
 * <p>The listing is complete only when the crawl was neither {@link
 * AutoindexCrawlerService.CrawlResult#truncated()} nor {@link
 * AutoindexCrawlerService.CrawlResult#incomplete()}; only then does the run frame reconcile, and
 * only then are the folders pruned afterwards.
 */
public class UrlIndexingExecutor implements SourceIndexingExecutor {

  private static final Logger log = LoggerFactory.getLogger(UrlIndexingExecutor.class);

  private final AutoindexCrawlerService crawlerService;
  private final BoundedDownloader downloader;
  private final FileProcessingService fileProcessingService;
  private final DocumentRepository documentRepository;
  private final CrawlProperties crawlProperties;
  private final LibraryFolderService folderService;
  private final IndexingRunTemplate runTemplate;

  public UrlIndexingExecutor(
      AutoindexCrawlerService crawlerService,
      BoundedDownloader downloader,
      FileProcessingService fileProcessingService,
      DocumentRepository documentRepository,
      CrawlProperties crawlProperties,
      LibraryFolderService folderService,
      IndexingRunTemplate runTemplate) {
    this.crawlerService = crawlerService;
    this.downloader = downloader;
    this.fileProcessingService = fileProcessingService;
    this.documentRepository = documentRepository;
    this.crawlProperties = crawlProperties;
    this.folderService = folderService;
    this.runTemplate = runTemplate;
  }

  @Override
  public IndexingSourceType sourceType() {
    return IndexingSourceType.HTTP_DIRECTORY;
  }

  @Override
  public Map<IndexingRunMode, VanishedDocumentPolicy> runModes() {
    // ADR-0023, Entscheidung 4: one mode only, "vollständig auflistend".
    return Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE);
  }

  @Override
  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode) {
    runTemplate.run(jobId, targetLibrary, runMode, this, this::crawlDirectory);
  }

  private ListingOutcome crawlDirectory(IndexingRun run) throws IOException, InterruptedException {
    KnowledgeLibrary targetLibrary = run.library();
    UrlIndexingRequest request = toUrlIndexingRequest(targetLibrary);
    ProxyAndCredentials config;
    try {
      config = ProxyAndCredentials.parse(request.proxy(), request.credentials());
    } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
      throw new IndexingRunFailedException(e.getMessage());
    }
    String proxyHost = config.proxyHost();
    int proxyPort = config.proxyPort();

    String url = request.url();
    if (!url.endsWith("/") && !hasFileExtension(url)) {
      url = url + "/";
    }
    log.info("Starting URL crawl of: {}", url);

    AutoindexCrawlerService.CrawlResult crawlResult =
        crawlerService.crawl(
            url, proxyHost, proxyPort, config.username(), config.password(), request.insecureSsl());
    List<AutoindexCrawlerService.CrawledFileEntry> allFiles = crawlResult.entries();
    log.info("Discovered {} files for URL indexing", allFiles.size());

    // A crawl capped by a configured limit, or one with a subtree it could not fetch, is visible
    // in the run's own protocol - either way the run's bestand is incomplete.
    if (crawlResult.truncated()) {
      run.events()
          .record(
              IndexingEventCategory.REJECTED,
              "Crawl wurde durch ein konfiguriertes Limit abgeschnitten (Tiefe oder Anzahl"
                  + " Einträge)",
              url);
    }
    if (crawlResult.incomplete()) {
      run.events()
          .record(
              IndexingEventCategory.REJECTED,
              "Mindestens ein Unterverzeichnis konnte nicht abgerufen werden - der Bestand dieses"
                  + " Laufs ist unvollständig",
              url);
    }
    // A link a directory page carried but the crawler refused to follow (foreign origin, or an
    // ascent above the start URL) - one event per link, so an operator can see what was left out.
    for (String rejectedLink : crawlResult.rejectedLinks()) {
      run.events()
          .record(
              IndexingEventCategory.REJECTED,
              "Link führt aus dem Verzeichnis der Quelle heraus (fremder Ursprung oder Pfad"
                  + " außerhalb der Start-URL) und wurde nicht verfolgt",
              rejectedLink);
    }

    run.progress().setTotal(allFiles.size());
    run.progress().report();

    HttpClient httpClient =
        SourceHttpClientFactory.buildHttpClient(proxyHost, proxyPort, request.insecureSsl());
    String authHeader =
        SourceHttpClientFactory.buildAuthHeader(config.username(), config.password());
    ReconcilingAttachmentAccess attachmentAccess = run.attachmentAccess();
    var folderMirror = new SourceFolderMirror(folderService, targetLibrary);
    String normalizedUrl = url;

    for (AutoindexCrawlerService.CrawledFileEntry entry : allFiles) {
      run.markPresent(entry.url());
      processEntry(
          run, entry, httpClient, authHeader, attachmentAccess, folderMirror, normalizedUrl);
      run.progress().report();
    }

    if (crawlResult.truncated() || crawlResult.incomplete()) {
      return ListingOutcome.incomplete(List.of());
    }
    // After the document cleanup: a folder left holding only a now-removed document is pruned in
    // this same run.
    run.afterReconciliation(reconciled -> folderMirror.prune());
    return ListingOutcome.complete();
  }

  /**
   * One crawled entry. Whether it is indexed at all is decided from its actual content, not from
   * its name in the listing - but only a bounded prefix is read to decide, never the whole file: a
   * directory listing routinely sits next to files nobody meant for indexing, and downloading each
   * of those in full before rejecting them would fill the temp partition. The one exception is a
   * prefix that ended inside an unresolved container, which carries no verdict at all - see {@link
   * SupportedDocumentFormats#decideForPrefix}; that transfer, like every other one here, is capped
   * at {@link CrawlProperties#maxFileSizeBytes()}.
   */
  private void processEntry(
      IndexingRun run,
      AutoindexCrawlerService.CrawledFileEntry entry,
      HttpClient httpClient,
      String authHeader,
      ReconcilingAttachmentAccess attachmentAccess,
      SourceFolderMirror folderMirror,
      String normalizedUrl) {
    KnowledgeLibrary targetLibrary = run.library();
    // Checked before any download; a document indexed before folders existed still picks up its
    // folder without being re-indexed.
    if (run.isUnchanged(entry.url(), entry.lastModified())) {
      log.info("Skipping unchanged URL document: {}", entry.name());
      mirrorFolder(targetLibrary, entry.url(), normalizedUrl, folderMirror);
      run.progress().recordSkipped();
      return;
    }

    Path tempFile = null;
    try {
      log.info("Processing URL document: {} ({})", entry.name(), entry.url());
      byte[] prefix =
          downloader.downloadPrefix(
              httpClient, authHeader, entry.url(), SupportedDocumentFormats.DETECTION_PREFIX_BYTES);
      // Holds whatever the decision below had to download in full to reach a verdict, so the
      // finally block deletes it even when detection on it fails - and so an accepted entry is
      // not transferred a second time.
      Path[] downloadedForDecision = new Path[1];
      SupportedDocumentFormats.ContentDecision decision;
      try {
        decision =
            decideForEntry(
                prefix,
                entry.name(),
                () ->
                    downloadedForDecision[0] =
                        downloader.download(
                            httpClient,
                            authHeader,
                            entry.url(),
                            entry.name(),
                            crawlProperties.maxFileSizeBytes()));
      } finally {
        tempFile = downloadedForDecision[0];
      }
      if (!decision.supported()) {
        log.info(
            "Rejecting URL document with an unsupported format: {} ({})",
            entry.name(),
            entry.url());
        run.events()
            .record(
                IndexingEventCategory.UNSUPPORTED_FORMAT,
                "Dateiformat wird nicht unterstützt",
                entry.url());
        run.progress().recordSkipped();
        return;
      }
      if (decision.extensionMismatch()) {
        // Indexed anyway, only reported.
        run.events()
            .record(
                IndexingEventCategory.FORMAT_MISMATCH,
                "Dateiendung passt nicht zum erkannten Inhalt (erkannt: "
                    + decision.detectedExtension()
                    + ")",
                entry.url());
      }

      if (tempFile == null) {
        tempFile =
            downloader.download(
                httpClient,
                authHeader,
                entry.url(),
                entry.name(),
                crawlProperties.maxFileSizeBytes());
      }
      long fileSize = Files.size(tempFile);
      FileProcessingResult result =
          fileProcessingService.ingest(
              DocumentIngest.builder(targetLibrary)
                  .file(tempFile, fileSize)
                  .filePath(entry.url())
                  .fileName(entry.name())
                  .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                  .context(attachmentAccess.sourceContext())
                  .changeMarker(entry.lastModified())
                  .build(),
              attachmentAccess);
      if (run.recordOutcome(result, entry.url())) {
        run.markReprocessed(entry.url());
        log.info("Indexed URL document: {}", entry.name());
      }
    } catch (BoundedDownloader.AttachmentTooLargeException e) {
      // Cut off at the configured cap, so no bytes past it ever reached the temp partition.
      // Skipped, not failed - a rejection like any other on this path, and the run continues.
      log.warn(
          "Rejecting URL document exceeding the size limit of {} bytes: {}",
          crawlProperties.maxFileSizeBytes(),
          entry.url());
      run.events().record(IndexingEventCategory.REJECTED, tooLargeMessage(), entry.url());
      run.progress().recordSkipped();
    } catch (TargetAddressValidator.TargetAddressBlockedException e) {
      // e.getMessage() is already German, user-facing. Skipped, not failed: the policy declining a
      // target is not a processing error.
      log.warn("URL document target rejected: {} ({})", entry.url(), e.getMessage());
      run.events().record(IndexingEventCategory.REJECTED, e.getMessage(), entry.url());
      run.progress().recordSkipped();
    } catch (Exception | Error e) {
      run.recordFailure(entry.url(), e);
    } finally {
      if (tempFile != null) {
        try {
          Files.deleteIfExists(tempFile);
        } catch (IOException e) {
          log.warn("Failed to delete temp file: {}", tempFile, e);
        }
      }
      // In the finally block, not after it: every early return above (unsupported format,
      // rejected target, oversized entry) must still assign the folder of an entry that already
      // has a document row from an earlier run.
      mirrorFolder(targetLibrary, entry.url(), normalizedUrl, folderMirror);
    }
  }

  /**
   * Assigns {@code entryUrl}'s document, and recursively its attachments, to the folder the crawled
   * URL path maps to (ADR-0020), materializing that chain on first use. Runs in the executor rather
   * than in {@code FileProcessingService#ingest}, because a folder must also be assigned to an
   * entry this run never handed over - one skipped as unchanged, or rejected - as long as a
   * document row exists. Failures are logged, never rethrown.
   */
  private void mirrorFolder(
      KnowledgeLibrary targetLibrary,
      String entryUrl,
      String normalizedUrl,
      SourceFolderMirror folderMirror) {
    try {
      Optional<Document> document =
          documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), entryUrl);
      if (document.isEmpty()) {
        return;
      }
      UrlFolderPath path = UrlFolderPath.of(normalizedUrl, entryUrl);
      if (path.rejected()) {
        // Mirrors AsyncIndexingExecutor's own "does not sit under sourcePath" case: the document
        // stays at the library root rather than being dropped or mapped to a made-up name.
        log.warn(
            "Cannot map URL path segment \"{}\" of {} to a folder name - leaving the document at"
                + " the library root",
            path.rejectedSegment(),
            entryUrl);
      }
      UUID folderId = folderMirror.folderFor(path.segments());
      applyFolder(document.get(), folderId);
      folderMirror.markSeen(folderId);
    } catch (Exception e) {
      log.warn("Failed to mirror the source folder of {}", entryUrl, e);
    }
  }

  /** Writes {@code folderId} onto {@code document} and every attachment below it, if changed. */
  private void applyFolder(Document document, UUID folderId) {
    if (!Objects.equals(document.getFolderId(), folderId)) {
      document.setFolderId(folderId);
      documentRepository.save(document);
    }
    for (Document child : documentRepository.findByParentDocumentId(document.getId())) {
      applyFolder(child, folderId);
    }
  }

  /**
   * The German run-protocol message for an entry cut off at {@link #crawlProperties}' size cap. The
   * limit is named in the largest unit that still states it exactly, so a configured value below
   * one MiB is never reported as "0 MiB".
   */
  private String tooLargeMessage() {
    return "Datei überschreitet die zulässige Größe von "
        + formatByteLimit(crawlProperties.maxFileSizeBytes())
        + " und wurde nicht indiziert";
  }

  private static String formatByteLimit(long bytes) {
    if (bytes % (1024 * 1024) == 0) {
      return (bytes / (1024 * 1024)) + " MiB";
    }
    if (bytes % 1024 == 0) {
      return (bytes / 1024) + " KiB";
    }
    return bytes + " Byte";
  }

  /**
   * Whether the URL's last path segment contains a dot, i.e. looks like a file with an extension;
   * query and fragment are stripped first, and no regex is used, so a long URL cannot overflow the
   * stack. {@code public} because {@code SourceConnectionTestService} reuses this exact check, so a
   * URL is normalised identically for the test and for the run it tests.
   */
  public static boolean hasFileExtension(String url) {
    int queryStart = url.indexOf('?');
    String path = queryStart >= 0 ? url.substring(0, queryStart) : url;
    int fragmentStart = path.indexOf('#');
    if (fragmentStart >= 0) {
      path = path.substring(0, fragmentStart);
    }
    int lastSlash = path.lastIndexOf('/');
    String lastSegment = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    return lastSegment.contains(".");
  }

  /**
   * Decides whether a crawled entry is supported from its content, never from {@code entryName} -
   * the same decision {@link #processEntry} makes. A leading byte sample normally settles it; only
   * a prefix ending inside an unresolved container makes {@code completeContent} download in full.
   * Public so the cross-package parity test exercises this call rather than a reimplementation.
   */
  public static SupportedDocumentFormats.ContentDecision decideForEntry(
      byte[] prefix, String entryName, SupportedDocumentFormats.CompleteContent completeContent)
      throws IOException, InterruptedException {
    return SupportedDocumentFormats.decideForPrefix(entryName, prefix, completeContent);
  }

  /**
   * Extracts this executor's own configuration from {@code targetLibrary} (ADR-0018) - the
   * library's persisted quellkonfiguration, not a per-request field. {@code getSourceCredentials()}
   * is already plaintext here, since {@code SourceCredentialsConverter} decrypts on load.
   * Package-private so {@code UrlIndexingExecutorCredentialsTest} can assert on it with a freshly
   * reloaded entity, over the same decryption path a real run takes.
   */
  static UrlIndexingRequest toUrlIndexingRequest(KnowledgeLibrary targetLibrary) {
    return new UrlIndexingRequest(
        targetLibrary.getSourceUrl(),
        targetLibrary.getSourceProxy(),
        targetLibrary.getSourceCredentials(),
        targetLibrary.isSourceInsecureSsl());
  }
}
