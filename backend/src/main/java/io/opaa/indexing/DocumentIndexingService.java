package io.opaa.indexing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentIndexingService {

  private static final Logger log = LoggerFactory.getLogger(DocumentIndexingService.class);

  private final DocumentService documentService;
  private final FileProcessingService fileProcessingService;
  private final IndexingJobService indexingJobService;
  private final IndexingProperties properties;

  public DocumentIndexingService(
      DocumentService documentService,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      IndexingProperties properties) {
    this.documentService = documentService;
    this.fileProcessingService = fileProcessingService;
    this.indexingJobService = indexingJobService;
    this.properties = properties;
  }

  public IndexingJob triggerIndexing() {
    var job = indexingJobService.startJob();
    int processed = 0;
    int failed = 0;

    try {
      Path documentDir = Path.of(properties.documentPath());
      List<Path> files = documentService.discoverFiles(documentDir);
      log.info("Discovered {} files for indexing in {}", files.size(), documentDir);

      for (Path file : files) {
        try {
          fileProcessingService.processFile(file);
          processed++;
        } catch (Exception e) {
          log.error("Failed to process file: {}", file, e);
          failed++;
        }
      }

      indexingJobService.completeJob(job.getId(), processed, failed);
    } catch (IOException e) {
      log.error("Failed to discover files", e);
      indexingJobService.failJob(job.getId(), e.getMessage());
    } catch (Exception e) {
      log.error("Indexing failed unexpectedly", e);
      indexingJobService.failJob(job.getId(), e.getMessage());
    }

    return indexingJobService
        .getLatestJob()
        .orElseThrow(() -> new IllegalStateException("Job not found after indexing"));
  }
}
