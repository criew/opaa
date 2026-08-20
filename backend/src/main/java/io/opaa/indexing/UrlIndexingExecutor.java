package io.opaa.indexing;

import io.opaa.library.KnowledgeLibrary;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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

  public UrlIndexingExecutor(
      AutoindexCrawlerService crawlerService,
      UrlFileDownloader downloader,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      DocumentRepository documentRepository,
      IndexingRunEventRepository indexingRunEventRepository) {
    this.crawlerService = crawlerService;
    this.downloader = downloader;
    this.fileProcessingService = fileProcessingService;
    this.indexingJobService = indexingJobService;
    this.documentRepository = documentRepository;
    this.indexingRunEventRepository = indexingRunEventRepository;
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
    var events = new IndexingRunEventRecorder(indexingRunEventRepository, jobId);

    try {
      // Parse proxy config
      String proxyHost = null;
      int proxyPort = -1;
      if (request.proxy() != null && !request.proxy().isBlank()) {
        int colonIdx = request.proxy().lastIndexOf(':');
        if (colonIdx > 0) {
          proxyHost = request.proxy().substring(0, colonIdx);
          proxyPort = Integer.parseInt(request.proxy().substring(colonIdx + 1));
        }
      }

      // Parse credentials
      String username = null;
      String password = null;
      if (request.credentials() != null && !request.credentials().isBlank()) {
        int colonIdx = request.credentials().indexOf(':');
        if (colonIdx > 0) {
          username = request.credentials().substring(0, colonIdx);
          password = request.credentials().substring(colonIdx + 1);
        }
      }

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

      // Step 2: Split into supported and rejected file types
      Map<Boolean, List<AutoindexCrawlerService.CrawledFileEntry>> byFormatSupport =
          allFiles.stream()
              .collect(Collectors.partitioningBy(UrlIndexingExecutor::isSupportedFormat));
      List<AutoindexCrawlerService.CrawledFileEntry> supportedFiles =
          byFormatSupport.getOrDefault(true, List.of());
      List<AutoindexCrawlerService.CrawledFileEntry> rejectedFiles =
          byFormatSupport.getOrDefault(false, List.of());

      log.info(
          "Discovered {} files ({} supported) for URL indexing",
          allFiles.size(),
          supportedFiles.size());

      // Issue #375: rejected documents are part of the job, not invisible. They count towards the
      // total and are reported as skipped, so nobody has to guess why the number of indexed
      // documents is lower than the number of files behind the URL. #513: each one now also
      // becomes its own UNSUPPORTED_FORMAT event.
      for (AutoindexCrawlerService.CrawledFileEntry rejected : rejectedFiles) {
        events.record(
            IndexingEventCategory.UNSUPPORTED_FORMAT,
            "Dateiformat wird nicht unterstuetzt",
            rejected.url());
      }
      progress.addSkipped(
          RejectedDocumentReporter.reportRejected(
              IndexingSourceType.HTTP_DIRECTORY,
              url,
              rejectedFiles.stream().map(AutoindexCrawlerService.CrawledFileEntry::name).toList()));

      progress.setTotal(allFiles.size());
      progress.report();

      // Build shared HttpClient and auth header for downloads
      HttpClient httpClient =
          AutoindexCrawlerService.buildHttpClient(proxyHost, proxyPort, request.insecureSsl());
      String authHeader = AutoindexCrawlerService.buildAuthHeader(username, password);

      // Step 3: Process each file
      for (AutoindexCrawlerService.CrawledFileEntry entry : supportedFiles) {
        // Check if document is unchanged before downloading (saves bandwidth)
        if (isUnchanged(entry.url(), entry.lastModified())) {
          log.info("Skipping unchanged URL document: {}", entry.name());
          progress.recordSkipped();
          progress.report();
          continue;
        }

        Path tempFile = null;
        try {
          log.info("Processing URL document: {} ({})", entry.name(), entry.url());
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

          if (result == FileProcessingResult.SKIPPED) {
            progress.recordSkipped();
          } else {
            progress.recordProcessed();
            log.info("Indexed URL document: {}", entry.name());
          }
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

      finalizeEvents(jobId, events);
      progress.complete();
    } catch (IOException | InterruptedException e) {
      log.error("URL indexing failed", e);
      finalizeEvents(jobId, events);
      progress.fail(e.getMessage());
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    } catch (Exception e) {
      log.error("URL indexing failed unexpectedly", e);
      finalizeEvents(jobId, events);
      progress.fail(e.getMessage());
    }
  }

  /**
   * Persists {@code events}' overflow count on the job, once, at the end of a run (#513) - a no-op
   * when nothing was truncated.
   */
  private void finalizeEvents(UUID jobId, IndexingRunEventRecorder events) {
    if (events.overflowCount() > 0) {
      indexingJobService.recordEventsTruncated(jobId, events.overflowCount());
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
   */
  private boolean isUnchanged(String remoteUrl, String lastModified) {
    Optional<Document> existing = documentRepository.findByFilePath(remoteUrl);
    return existing.isPresent()
        && lastModified.equals(existing.get().getLastModifiedRemote())
        && existing.get().getStatus() == DocumentStatus.INDEXED;
  }

  static boolean isSupportedFormat(AutoindexCrawlerService.CrawledFileEntry entry) {
    return SupportedDocumentFormats.isSupported(entry.name());
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
