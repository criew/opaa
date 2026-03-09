package io.opaa.indexing;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

/** Async executor for URL-based document indexing via Apache mod_autoindex crawling. */
public class UrlIndexingExecutor {

  private static final Logger log = LoggerFactory.getLogger(UrlIndexingExecutor.class);

  private static final Set<String> SUPPORTED_EXTENSIONS =
      Set.of(".md", ".txt", ".pdf", ".docx", ".pptx", ".doc");

  private final AutoindexCrawlerService crawlerService;
  private final UrlFileDownloader downloader;
  private final FileProcessingService fileProcessingService;
  private final IndexingJobService indexingJobService;
  private final DocumentRepository documentRepository;

  public UrlIndexingExecutor(
      AutoindexCrawlerService crawlerService,
      UrlFileDownloader downloader,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      DocumentRepository documentRepository) {
    this.crawlerService = crawlerService;
    this.downloader = downloader;
    this.fileProcessingService = fileProcessingService;
    this.indexingJobService = indexingJobService;
    this.documentRepository = documentRepository;
  }

  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, UrlIndexingRequest request) {
    int processed = 0;
    int failed = 0;
    int skipped = 0;

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

      // Step 2: Filter to supported file types
      List<AutoindexCrawlerService.CrawledFileEntry> supportedFiles =
          allFiles.stream().filter(this::isSupportedFormat).toList();

      log.info(
          "Discovered {} files ({} supported) for URL indexing",
          allFiles.size(),
          supportedFiles.size());

      indexingJobService.setTotalDocuments(jobId, supportedFiles.size());

      // Build shared HttpClient and auth header for downloads
      HttpClient httpClient =
          AutoindexCrawlerService.buildHttpClient(proxyHost, proxyPort, request.insecureSsl());
      String authHeader = AutoindexCrawlerService.buildAuthHeader(username, password);

      // Step 3: Process each file
      for (AutoindexCrawlerService.CrawledFileEntry entry : supportedFiles) {
        // Check if document is unchanged before downloading (saves bandwidth)
        if (isUnchanged(entry.url(), entry.lastModified())) {
          log.info("Skipping unchanged URL document: {}", entry.name());
          skipped++;
          indexingJobService.updateProgress(jobId, processed, failed, skipped);
          continue;
        }

        Path tempFile = null;
        try {
          log.info("Processing URL document: {} ({})", entry.name(), entry.url());
          tempFile = downloader.download(httpClient, authHeader, entry.url(), entry.name());

          long fileSize = Files.size(tempFile);
          FileProcessingResult result =
              fileProcessingService.processUrlFile(
                  tempFile, entry.url(), entry.lastModified(), fileSize);

          if (result == FileProcessingResult.SKIPPED) {
            skipped++;
          } else {
            processed++;
            log.info("Indexed URL document: {}", entry.name());
          }
        } catch (Exception e) {
          log.error("Failed to process URL document: {} ({})", entry.name(), entry.url(), e);
          failed++;
        } catch (Error e) {
          log.error(
              "Fatal error while processing URL document: {} ({})", entry.name(), entry.url(), e);
          failed++;
        } finally {
          if (tempFile != null) {
            try {
              Files.deleteIfExists(tempFile);
            } catch (IOException e) {
              log.warn("Failed to delete temp file: {}", tempFile, e);
            }
          }
        }
        indexingJobService.updateProgress(jobId, processed, failed, skipped);
      }

      indexingJobService.completeJob(jobId, processed, failed, skipped);
    } catch (IOException | InterruptedException e) {
      log.error("URL indexing failed", e);
      indexingJobService.failJob(jobId, e.getMessage());
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    } catch (Exception e) {
      log.error("URL indexing failed unexpectedly", e);
      indexingJobService.failJob(jobId, e.getMessage());
    }
  }

  /**
   * Returns true if the URL's last path segment contains a dot (i.e. looks like a file with an
   * extension). Query strings and fragments are stripped before checking. Avoids regex to prevent
   * StackOverflowError on long URLs.
   */
  static boolean hasFileExtension(String url) {
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

  private boolean isSupportedFormat(AutoindexCrawlerService.CrawledFileEntry entry) {
    String name = entry.name().toLowerCase();
    return SUPPORTED_EXTENSIONS.stream().anyMatch(name::endsWith);
  }
}
