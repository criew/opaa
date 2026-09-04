package io.opaa.indexing.source.web;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.DocumentService;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingRunEventRecorder;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.IndexingRunProgress;
import io.opaa.indexing.StaleDocumentCleanupService;
import io.opaa.indexing.SupportedDocumentFormats;
import io.opaa.indexing.source.IndexingSourceType;
import io.opaa.indexing.source.SourceFolderMirror;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryFolderService;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.ProxyAndCredentials;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

/**
 * Executes indexing runs for {@link IndexingSourceType#HTTP_DIRECTORY} via Apache mod_autoindex
 * crawling (ADR-0017).
 *
 * <p>The crawled directory structure is mirrored into {@code library_folders} (ADR-0020, #1277)
 * through the same {@link io.opaa.indexing.source.SourceFolderMirror} the FILESYSTEM executor uses:
 * a document's folder path is its URL path relative to the normalized start URL, see {@link
 * UrlFolderPath}. Folders are pruned under the very same completeness condition as the document
 * cleanup below, and only after it.
 *
 * <p>Once every crawled entry has been processed, {@link
 * StaleDocumentCleanupService#cleanupVanished} removes every {@code HTTP_DIRECTORY} document of
 * this library whose URL was not in this run's own crawl result - it no longer exists at the source
 * (#886). This method skips the call entirely when either of the following holds, since each one
 * means {@code allFiles} is not a trustworthy stand-in for the source's complete bestand:
 *
 * <ul>
 *   <li>{@link AutoindexCrawlerService.CrawlResult#truncated()} - a depth/entry limit cut the crawl
 *       short, so anything beyond the cut would incorrectly look vanished.
 *   <li>{@link AutoindexCrawlerService.CrawlResult#incomplete()} - at least one subdirectory could
 *       not be fetched at all (#886 review); every document under that subtree would otherwise look
 *       vanished even though the crawl simply never reached it.
 * </ul>
 *
 * {@code cleanupVanished} itself additionally refuses an empty {@code currentUrls} (#886 review) -
 * a root page answering with zero entries is indistinguishable here from an unreachable or
 * misconfigured source (a maintenance page returning {@code 200}, a misconfigured redirect target),
 * so this guard lives in the shared service rather than being duplicated per executor. Also only
 * reached on this method's own success path, so a failed or crashed run never deletes anything.
 */
public class UrlIndexingExecutor implements SourceIndexingExecutor {

  private static final Logger log = LoggerFactory.getLogger(UrlIndexingExecutor.class);

  private final AutoindexCrawlerService crawlerService;
  private final BoundedDownloader downloader;
  private final FileProcessingService fileProcessingService;
  private final IndexingJobService indexingJobService;
  private final DocumentRepository documentRepository;
  private final IndexingRunEventRepository indexingRunEventRepository;
  private final LibraryStorageQuotaService storageQuotaService;
  private final StaleDocumentCleanupService staleDocumentCleanupService;
  private final CrawlProperties crawlProperties;
  private final LibraryFolderService folderService;

  public UrlIndexingExecutor(
      AutoindexCrawlerService crawlerService,
      BoundedDownloader downloader,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      DocumentRepository documentRepository,
      IndexingRunEventRepository indexingRunEventRepository,
      LibraryStorageQuotaService storageQuotaService,
      StaleDocumentCleanupService staleDocumentCleanupService,
      CrawlProperties crawlProperties,
      LibraryFolderService folderService) {
    this.crawlerService = crawlerService;
    this.downloader = downloader;
    this.fileProcessingService = fileProcessingService;
    this.indexingJobService = indexingJobService;
    this.documentRepository = documentRepository;
    this.indexingRunEventRepository = indexingRunEventRepository;
    this.storageQuotaService = storageQuotaService;
    this.staleDocumentCleanupService = staleDocumentCleanupService;
    this.crawlProperties = crawlProperties;
    this.folderService = folderService;
  }

  @Override
  public IndexingSourceType sourceType() {
    return IndexingSourceType.HTTP_DIRECTORY;
  }

  @Override
  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary) {
    UrlIndexingRequest request = toUrlIndexingRequest(targetLibrary);
    var progress = new IndexingRunProgress(indexingJobService, jobId);
    var events =
        new IndexingRunEventRecorder(indexingRunEventRepository, indexingJobService, jobId);

    try {
      // Parsing goes through the shared ProxyAndCredentials rather than an inline copy, mirroring
      // RssFeedIndexingExecutor#execute - an invalid sourceProxy port gets an understandable
      // German message here instead of the JDK's raw NumberFormatException text.
      ProxyAndCredentials config;
      try {
        config = ProxyAndCredentials.parse(request.proxy(), request.credentials());
      } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
        progress.fail(e.getMessage());
        return;
      }
      String proxyHost = config.proxyHost();
      int proxyPort = config.proxyPort();
      String username = config.username();
      String password = config.password();

      // Normalize URL
      String url = request.url();
      if (!url.endsWith("/") && !hasFileExtension(url)) {
        url = url + "/";
      }

      log.info("Starting URL crawl of: {}", url);

      // Step 1: Crawl directory listing
      AutoindexCrawlerService.CrawlResult crawlResult =
          crawlerService.crawl(
              url, proxyHost, proxyPort, username, password, request.insecureSsl());
      List<AutoindexCrawlerService.CrawledFileEntry> allFiles = crawlResult.entries();

      log.info("Discovered {} files for URL indexing", allFiles.size());

      // A run capped by CrawlProperties' depth or entry limit is only visible in the application
      // log otherwise - recorded as REJECTED so the run's own protocol in the UI can tell a
      // truncated crawl apart from a genuinely complete one.
      if (crawlResult.truncated()) {
        events.record(
            IndexingEventCategory.REJECTED,
            "Crawl wurde durch ein konfiguriertes Limit abgeschnitten (Tiefe oder Anzahl Einträge)",
            url);
      }
      // #886 review: a subtree this run could not fetch at all is a different reason than a
      // configured limit, but has the same consequence for stale-document cleanup below - the
      // run's own bestand is incomplete either way.
      if (crawlResult.incomplete()) {
        events.record(
            IndexingEventCategory.REJECTED,
            "Mindestens ein Unterverzeichnis konnte nicht abgerufen werden - der Bestand dieses"
                + " Laufs ist unvollständig",
            url);
      }

      progress.setTotal(allFiles.size());
      progress.report();

      // Build shared HttpClient and auth header for downloads
      HttpClient httpClient =
          SourceHttpClientFactory.buildHttpClient(proxyHost, proxyPort, request.insecureSsl());
      String authHeader = SourceHttpClientFactory.buildAuthHeader(username, password);

      // What the attachment path created or confirmed this run, and which of those were actually
      // re-parsed - feeds the vanished-cleanup bookkeeping below (ADR-0022, Entscheidung 3),
      // mirroring AsyncIndexingExecutor's FILESYSTEM counterpart (#1219).
      Set<String> indexedAttachmentPaths = new HashSet<>();
      Set<String> reprocessedAttachmentPaths = new HashSet<>();
      var attachmentAccess =
          new WebAttachmentAccess(
              targetLibrary, events, progress, indexedAttachmentPaths, reprocessedAttachmentPaths);
      // Entries whose content was actually (re-)parsed this run - their attachment set was freshly
      // enumerated, so only the attachment paths recorded above count for them.
      Set<String> reprocessedEntryUrls = new HashSet<>();

      // Mirrors the crawled directory structure into library_folders (ADR-0020, #1277) - the same
      // helper AsyncIndexingExecutor drives for FILESYSTEM. normalizedUrl is the path every entry
      // URL is made relative to.
      var folderMirror = new SourceFolderMirror(folderService, targetLibrary);
      String normalizedUrl = url;

      // Step 2: Process each file. Whether a file is indexed at all is decided from its actual
      // content, not from its name in the listing - but only a bounded prefix is read to decide,
      // never the whole file: a directory listing routinely sits next to files nobody meant for
      // indexing at all, and downloading each of those in full before rejecting them would fill
      // the temp partition. The one exception is a prefix that ended inside an unresolved
      // container, which carries no verdict at all - see SupportedDocumentFormats#decideForPrefix;
      // that transfer, like every other one here, is capped at CrawlProperties#maxFileSizeBytes.
      for (AutoindexCrawlerService.CrawledFileEntry entry : allFiles) {
        // Check if document is unchanged before downloading (saves bandwidth)
        if (isUnchanged(entry.url(), entry.lastModified(), targetLibrary)) {
          log.info("Skipping unchanged URL document: {}", entry.name());
          // Runs even here, before the download is skipped: a document indexed before #1277 (or
          // one whose directory moved) picks up its folder without being re-indexed.
          mirrorFolder(targetLibrary, entry.url(), normalizedUrl, folderMirror);
          progress.recordSkipped();
          progress.report();
          continue;
        }

        Path tempFile = null;
        try {
          log.info("Processing URL document: {} ({})", entry.name(), entry.url());

          byte[] prefix =
              downloader.downloadPrefix(
                  httpClient,
                  authHeader,
                  entry.url(),
                  SupportedDocumentFormats.DETECTION_PREFIX_BYTES);
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
            // Rejected documents are part of the job, not invisible - each one becomes its own
            // UNSUPPORTED_FORMAT event. Reached before the full transfer for every entry the
            // prefix alone could decide, which is all but the unresolved-container case above.
            log.info(
                "Rejecting URL document with an unsupported format: {} ({})",
                entry.name(),
                entry.url());
            events.record(
                IndexingEventCategory.UNSUPPORTED_FORMAT,
                "Dateiformat wird nicht unterstützt",
                entry.url());
            progress.recordSkipped();
            progress.report();
            continue;
          }
          if (decision.extensionMismatch()) {
            // Indexed anyway, only reported.
            events.record(
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
              fileProcessingService.processUrlFile(
                  tempFile,
                  entry.name(),
                  entry.url(),
                  entry.lastModified(),
                  fileSize,
                  targetLibrary,
                  DocumentSourceType.HTTP_DIRECTORY,
                  null,
                  null,
                  attachmentAccess);

          if (result == FileProcessingResult.QUOTA_EXCEEDED) {
            // See AsyncIndexingExecutor's own handling of this outcome.
            events.record(
                IndexingEventCategory.REJECTED,
                storageQuotaService.quotaExceededMessage(targetLibrary.getId()),
                entry.url());
            progress.recordSkipped();
          } else if (result == FileProcessingResult.NO_EXTRACTABLE_TEXT) {
            // See AsyncIndexingExecutor's own handling of this outcome.
            events.record(
                IndexingEventCategory.REJECTED,
                DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE,
                entry.url());
            progress.recordSkipped();
          } else if (result == FileProcessingResult.FAILED) {
            // See AsyncIndexingExecutor's own handling of this outcome.
            events.record(IndexingEventCategory.ERROR, "Verarbeitung fehlgeschlagen", entry.url());
            progress.recordFailed();
          } else if (result == FileProcessingResult.SKIPPED) {
            progress.recordSkipped();
          } else {
            reprocessedEntryUrls.add(entry.url());
            progress.recordProcessed();
            log.info("Indexed URL document: {}", entry.name());
          }
        } catch (BoundedDownloader.AttachmentTooLargeException e) {
          // #1236: the transfer was cut off at the configured cap, so no bytes past it ever
          // reached the temp partition. Skipped, not failed - an entry too large for this
          // installation is a rejection like any other on this path, and the run continues.
          log.warn(
              "Rejecting URL document exceeding the size limit of {} bytes: {}",
              crawlProperties.maxFileSizeBytes(),
              entry.url());
          events.record(IndexingEventCategory.REJECTED, tooLargeMessage(), entry.url());
          progress.recordSkipped();
        } catch (TargetAddressValidator.TargetAddressBlockedException e) {
          // e.getMessage() is already German, user-facing (see TargetAddressValidator's Javadoc).
          // Treated as skipped, not failed - mirrors RssFeedIndexingExecutor's identical policy
          // rejections, which are the remote/policy declining a target, not a processing error.
          log.warn("URL document target rejected: {} ({})", entry.url(), e.getMessage());
          events.record(IndexingEventCategory.REJECTED, e.getMessage(), entry.url());
          progress.recordSkipped();
        } catch (Exception e) {
          log.error("Failed to process URL document: {} ({})", entry.name(), entry.url(), e);
          events.record(IndexingEventCategory.ERROR, "Verarbeitung fehlgeschlagen", entry.url());
          progress.recordFailed();
        } catch (Error e) {
          log.error(
              "Fatal error while processing URL document: {} ({})", entry.name(), entry.url(), e);
          events.record(IndexingEventCategory.ERROR, "Verarbeitung fehlgeschlagen", entry.url());
          progress.recordFailed();
        } finally {
          if (tempFile != null) {
            try {
              Files.deleteIfExists(tempFile);
            } catch (IOException e) {
              log.warn("Failed to delete temp file: {}", tempFile, e);
            }
          }
          // In the finally block, not after it: every early `continue` above (unsupported format,
          // rejected target, oversized entry) must still assign the folder of an entry that
          // already has a document row from an earlier run.
          mirrorFolder(targetLibrary, entry.url(), normalizedUrl, folderMirror);
        }
        progress.report();
      }

      // See this class' own Javadoc: skipped for a truncated or incomplete crawl (#886/#886
      // review), only reached on the success path. An empty currentUrls is additionally guarded
      // inside cleanupVanished itself, not duplicated here.
      if (!crawlResult.truncated() && !crawlResult.incomplete()) {
        try {
          Set<String> currentUrls =
              allFiles.stream()
                  .map(AutoindexCrawlerService.CrawledFileEntry::url)
                  .collect(Collectors.toCollection(HashSet::new));
          // ADR-0022, Entscheidung 3, mirroring AsyncIndexingExecutor (#1219): an attachment
          // counts as present this run either because the attachment path itself
          // created/confirmed it while its parent was re-parsed (indexedAttachmentPaths), or -
          // the Nachtragsfall - because its parent still exists but was NOT re-parsed this run
          // (unchanged, checksum-skipped, rejected, failed), so its existing attachment rows must
          // be preserved from the database. An attachment of a re-parsed parent that was NOT
          // re-reported is genuinely gone (removed from the mail) and is deliberately not folded
          // in, so cleanupVanished below removes it. A truncated or incomplete crawl skips this
          // whole block, attachments included - their parents' presence is unknowable then too.
          currentUrls.addAll(indexedAttachmentPaths);
          Set<String> reprocessedPaths = new HashSet<>(reprocessedEntryUrls);
          reprocessedPaths.addAll(reprocessedAttachmentPaths);
          List<Document> existingHttpDocuments =
              documentRepository.findByLibraryIdAndSourceType(
                  targetLibrary.getId(), DocumentSourceType.HTTP_DIRECTORY);
          StaleDocumentCleanupService.foldInPreservedAttachmentPaths(
              existingHttpDocuments, currentUrls, reprocessedPaths);
          staleDocumentCleanupService.cleanupVanished(
              targetLibrary, DocumentSourceType.HTTP_DIRECTORY, currentUrls, events);
        } catch (Exception e) {
          log.warn(
              "Failed to clean up vanished HTTP_DIRECTORY documents for library {}",
              targetLibrary.getId(),
              e);
        }
        // After the document cleanup, mirroring AsyncIndexingExecutor's order: a folder left
        // holding only a now-removed document is pruned in this same run. Inside the
        // complete-run guard, so a truncated or incomplete crawl never removes a folder whose
        // documents it simply never saw.
        folderMirror.prune();
      }

      events.finalizeRun();
      progress.complete();
    } catch (IOException | InterruptedException e) {
      log.error("URL indexing failed", e);
      events.finalizeRun();
      progress.fail(e.getMessage());
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    } catch (Exception e) {
      log.error("URL indexing failed unexpectedly", e);
      events.finalizeRun();
      progress.fail(e.getMessage());
    }
  }

  /**
   * Assigns {@code entryUrl}'s document (and, recursively, its attachments - ADR-0022: an
   * attachment belongs into its parent mail's folder) to the folder the crawled URL path maps to
   * (ADR-0020, #1277), materializing that folder chain on first use.
   *
   * <p>Runs in the executor rather than inside {@code FileProcessingService#processUrlFile}: the
   * folder must also be assigned to an entry this run never handed to that method at all - one
   * skipped undownloaded because {@code Last-Modified} was unchanged, or one rejected for its
   * format, target or size - as long as an earlier run left a document row behind. That is the same
   * nachtrag {@code AsyncIndexingExecutor} gets from {@code processFile}'s own SKIPPED branch.
   * Without a document row nothing is materialized, so a directory holding only never-indexed
   * entries produces no folder.
   *
   * <p>Failures are logged, never rethrown - a folder assignment must not fail an entry whose
   * content was indexed successfully.
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
   * Returns true if the URL's last path segment contains a dot (i.e. looks like a file with an
   * extension). Query strings and fragments are stripped before checking. Avoids regex to prevent
   * StackOverflowError on long URLs.
   *
   * <p>{@code public}, not package-private: {@code SourceConnectionTestService} reuses this exact
   * check so a URL like {@code https://host/dateien/index.html} is normalised identically for the
   * test and for the run it is testing.
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
   * Checks if a URL document exists and is unchanged based on the lastModified date from the
   * directory listing. This avoids downloading the file when it hasn't changed. After download, the
   * SHA-256 checksum provides an additional content-based verification layer.
   *
   * <p>A blank {@code lastModified} means "unknown", not "unchanged": the {@code <ul>}-based
   * autoindex layouts ({@code IndexOptions -FancyIndexing}, Python's {@code http.server}) never
   * carry a date at all, so {@link AutoindexCrawlerService} reports it as an empty string every
   * run. Treating two empty strings as equal would mean such a source is fetched once and never
   * re-fetched again.
   *
   * <p>The lookup is scoped to {@code targetLibrary} (#877): the same URL indexed into a different
   * library is an independent document, so this never reports "unchanged" for a document that
   * belongs to another library. The RSS path ({@code RssFeedIndexingExecutor#isUnchanged}) mirrors
   * this too.
   */
  public boolean isUnchanged(
      String remoteUrl, String lastModified, KnowledgeLibrary targetLibrary) {
    if (lastModified == null || lastModified.isBlank()) {
      return false;
    }
    Optional<Document> existing =
        documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), remoteUrl);
    return existing.isPresent()
        && lastModified.equals(existing.get().getLastModifiedRemote())
        && existing.get().getStatus() == DocumentStatus.INDEXED;
  }

  /**
   * Decides whether a crawled entry is supported from its content, never from {@code entryName}
   * alone - the same decision {@link #execute} makes before an entry enters the pipeline. Normally
   * a leading byte sample settles it; only a prefix that ended inside an unresolved container makes
   * {@code completeContent} download the entry in full to decide (see {@link
   * SupportedDocumentFormats#decideForPrefix}). Public so the cross-package parity test exercises
   * this exact call instead of a reimplementation that could silently drift from it.
   */
  public static SupportedDocumentFormats.ContentDecision decideForEntry(
      byte[] prefix, String entryName, SupportedDocumentFormats.CompleteContent completeContent)
      throws IOException, InterruptedException {
    return SupportedDocumentFormats.decideForPrefix(entryName, prefix, completeContent);
  }

  /**
   * Extracts this executor's own configuration ({@code sourceUrl}/{@code sourceProxy}/{@code
   * sourceCredentials}/{@code sourceInsecureSsl}) from {@code targetLibrary} (ADR-0018) - the
   * library's persisted quellkonfiguration, not a per-request field. {@code
   * targetLibrary.getSourceCredentials()} is already plaintext at this point regardless of whether
   * the underlying row is encrypted - {@code SourceCredentialsConverter} decrypts transparently
   * when the entity is loaded.
   *
   * <p>Package-private (not {@code private}) solely so {@code UrlIndexingExecutorCredentialsTest}
   * can assert on it directly with a library entity freshly reloaded from the database, the same
   * decryption path a real run takes.
   */
  static UrlIndexingRequest toUrlIndexingRequest(KnowledgeLibrary targetLibrary) {
    return new UrlIndexingRequest(
        targetLibrary.getSourceUrl(),
        targetLibrary.getSourceProxy(),
        targetLibrary.getSourceCredentials(),
        targetLibrary.isSourceInsecureSsl());
  }
}
