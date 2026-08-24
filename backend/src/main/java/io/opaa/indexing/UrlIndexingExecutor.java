package io.opaa.indexing;

import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

/**
 * Executes indexing runs for {@link IndexingSourceType#HTTP_DIRECTORY} via Apache mod_autoindex
 * crawling (ADR-0017).
 */
public class UrlIndexingExecutor implements SourceIndexingExecutor {

  private static final Logger log = LoggerFactory.getLogger(UrlIndexingExecutor.class);

  private final AutoindexCrawlerService crawlerService;
  private final UrlFileDownloader downloader;
  private final FileProcessingService fileProcessingService;
  private final IndexingJobService indexingJobService;
  private final DocumentRepository documentRepository;
  private final IndexingRunEventRepository indexingRunEventRepository;
  private final LibraryStorageQuotaService storageQuotaService;

  public UrlIndexingExecutor(
      AutoindexCrawlerService crawlerService,
      UrlFileDownloader downloader,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      DocumentRepository documentRepository,
      IndexingRunEventRepository indexingRunEventRepository,
      LibraryStorageQuotaService storageQuotaService) {
    this.crawlerService = crawlerService;
    this.downloader = downloader;
    this.fileProcessingService = fileProcessingService;
    this.indexingJobService = indexingJobService;
    this.documentRepository = documentRepository;
    this.indexingRunEventRepository = indexingRunEventRepository;
    this.storageQuotaService = storageQuotaService;
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
      // Issue #839: parsing goes through the shared ProxyAndCredentials rather than an inline
      // copy, mirroring RssFeedIndexingExecutor#execute (PR #642 review, finding 4) - an invalid
      // sourceProxy port is now a controlled failure instead of an unhandled
      // NumberFormatException.
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
      List<AutoindexCrawlerService.CrawledFileEntry> allFiles =
          crawlerService.crawl(
              url, proxyHost, proxyPort, username, password, request.insecureSsl());

      log.info("Discovered {} files for URL indexing", allFiles.size());

      progress.setTotal(allFiles.size());
      progress.report();

      // Build shared HttpClient and auth header for downloads
      HttpClient httpClient =
          AutoindexCrawlerService.buildHttpClient(proxyHost, proxyPort, request.insecureSsl());
      String authHeader = AutoindexCrawlerService.buildAuthHeader(username, password);

      // Step 2: Process each file. #404: whether a file is indexed at all is decided from its
      // actual content, not from its name in the listing - but only a bounded prefix is read to
      // decide (#404 review, finding 1), never the whole file: a directory listing routinely sits
      // next to files nobody meant for indexing at all (an ISO image, a video, a backup archive),
      // and downloading each of those in full before rejecting them would fill the temp
      // partition and drastically slow every run down. Only an entry the prefix decision already
      // accepts is downloaded in full via #download below.
      for (AutoindexCrawlerService.CrawledFileEntry entry : allFiles) {
        // Check if document is unchanged before downloading (saves bandwidth)
        if (isUnchanged(entry.url(), entry.lastModified(), targetLibrary)) {
          log.info("Skipping unchanged URL document: {}", entry.name());
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
          SupportedDocumentFormats.ContentDecision decision =
              SupportedDocumentFormats.decideForFileName(
                  entry.name(), SupportedDocumentFormats.detectMediaType(prefix));
          if (!decision.supported()) {
            // Issue #375: rejected documents are part of the job, not invisible. #513: each one
            // becomes its own UNSUPPORTED_FORMAT event. Rejected here, before #download ever
            // runs - the full file behind this entry, whatever its actual size, is never
            // transferred (#404 review, finding 1).
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
            // #404 acceptance criteria: indexed anyway, only reported.
            events.record(
                IndexingEventCategory.FORMAT_MISMATCH,
                "Dateiendung passt nicht zum erkannten Inhalt (erkannt: "
                    + decision.detectedExtension()
                    + ")",
                entry.url());
          }

          tempFile = downloader.download(httpClient, authHeader, entry.url(), entry.name());
          long fileSize = Files.size(tempFile);
          FileProcessingResult result =
              fileProcessingService.processUrlFile(
                  tempFile,
                  entry.name(),
                  entry.url(),
                  entry.lastModified(),
                  fileSize,
                  targetLibrary);

          if (result == FileProcessingResult.QUOTA_EXCEEDED) {
            // #119: see AsyncIndexingExecutor's own handling of this outcome.
            events.record(
                IndexingEventCategory.REJECTED,
                storageQuotaService.quotaExceededMessage(targetLibrary.getId()),
                entry.url());
            progress.recordSkipped();
          } else if (result == FileProcessingResult.SKIPPED) {
            progress.recordSkipped();
          } else {
            progress.recordProcessed();
            log.info("Indexed URL document: {}", entry.name());
          }
        } catch (TargetAddressValidator.TargetAddressBlockedException e) {
          // #267: e.getMessage() is already German, user-facing and never carries more than the
          // rejected host itself (see TargetAddressValidator's own Javadoc) - safe to show as-is.
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
        }
        progress.report();
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
   * Returns true if the URL's last path segment contains a dot (i.e. looks like a file with an
   * extension). Query strings and fragments are stripped before checking. Avoids regex to prevent
   * StackOverflowError on long URLs.
   *
   * <p>{@code public}, not package-private (PR #537 review, nit 5): {@code
   * SourceConnectionTestService} reuses this exact check so a URL like {@code
   * https://host/dateien/index.html} is normalised identically for the test and for the run it is
   * testing - a mismatch here previously produced a false negative (the test appended a trailing
   * slash unconditionally, turning a working address into a 404).
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
   * <p>A blank {@code lastModified} (#550 review) means "unknown", not "unchanged": the {@code
   * <ul>}-based autoindex layouts ({@code IndexOptions -FancyIndexing}, Python's {@code
   * http.server}) never carry a date at all, so {@link AutoindexCrawlerService} reports it as an
   * empty string every run. Treating two empty strings as equal would mean such a source is fetched
   * once and then never re-fetched again, no matter how the remote file actually changes - always
   * re-fetching when the signal is missing is the only safe fallback.
   *
   * <p>#491: mirrors the same check {@code FileProcessingService#processUrlFile} makes (library
   * changed -> not unchanged) - without it, indexing the same source into a different target
   * library never took effect for a document whose {@code lastModified} is otherwise unchanged,
   * because this check runs before the download (and {@code processUrlFile}) is ever reached, and
   * the document stayed behind in its previous library. The RSS path ({@link
   * RssFeedIndexingExecutor#isUnchanged}) closed the equivalent gap in #490's review.
   */
  boolean isUnchanged(String remoteUrl, String lastModified, KnowledgeLibrary targetLibrary) {
    if (lastModified == null || lastModified.isBlank()) {
      return false;
    }
    Optional<Document> existing = documentRepository.findByFilePath(remoteUrl);
    return existing.isPresent()
        && lastModified.equals(existing.get().getLastModifiedRemote())
        && existing.get().getStatus() == DocumentStatus.INDEXED
        && targetLibrary.getId().equals(existing.get().getLibraryId());
  }

  /**
   * Classifies an already-downloaded file from its actual content (#404) - the network path's own
   * call into {@link SupportedDocumentFormats#decideForFileName}, mirroring {@link
   * DocumentService}'s equivalent for the filesystem path so both paths can never diverge on what
   * "supported" means. {@code entryName} (the listing's own file name) is only the hint used for
   * {@link SupportedDocumentFormats.ContentDecision#extensionMismatch()}, never for acceptance
   * itself. Package-visible for {@code DocumentFormatParityTest}.
   */
  static SupportedDocumentFormats.ContentDecision classifyDownloadedFile(
      Path downloadedFile, String entryName) throws IOException {
    String detectedMimeType = SupportedDocumentFormats.detectMediaType(downloadedFile);
    return SupportedDocumentFormats.decideForFileName(entryName, detectedMimeType);
  }

  /**
   * Extracts this executor's own configuration ({@code sourceUrl}/{@code sourceProxy}/{@code
   * sourceCredentials}/{@code sourceInsecureSsl}) from {@code targetLibrary} (ADR-0018, #478) - the
   * library's persisted quellkonfiguration, not a per-request field any more. {@code
   * targetLibrary.getSourceCredentials()} is already plaintext at this point regardless of whether
   * the underlying row is encrypted (#483) - {@code SourceCredentialsConverter} decrypts
   * transparently when the entity is loaded, so this method needs no awareness of encryption at
   * all.
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
