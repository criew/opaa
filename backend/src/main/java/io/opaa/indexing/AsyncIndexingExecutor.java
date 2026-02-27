package io.opaa.indexing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

public class AsyncIndexingExecutor {

  private static final Logger log = LoggerFactory.getLogger(AsyncIndexingExecutor.class);

  private final DocumentService documentService;
  private final FileProcessingService fileProcessingService;
  private final IndexingJobService indexingJobService;
  private final IndexingProperties properties;

  public AsyncIndexingExecutor(
      DocumentService documentService,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      IndexingProperties properties) {
    this.documentService = documentService;
    this.fileProcessingService = fileProcessingService;
    this.indexingJobService = indexingJobService;
    this.properties = properties;
  }

  @Async("indexingTaskExecutor")
  public void execute(UUID jobId) {
    int processed = 0;
    int failed = 0;
    int skipped = 0;

    try {
      Path documentDir = Path.of(properties.documentPath());
      List<Path> files = documentService.discoverFiles(documentDir);
      log.info("Discovered {} files for indexing in {}", files.size(), documentDir);

      indexingJobService.setTotalDocuments(jobId, files.size());

      for (Path file : files) {
        String fileName = file.getFileName().toString();
        try {
          log.info("Processing: {}", fileName);
          FileProcessingResult result = fileProcessingService.processFile(file);
          if (result == FileProcessingResult.SKIPPED) {
            skipped++;
          } else {
            processed++;
            log.info("Indexing completed: {}", fileName);
          }
        } catch (Exception e) {
          log.error("Failed to process file: {}", fileName, e);
          failed++;
        }
        indexingJobService.updateProgress(jobId, processed, failed, skipped);
      }

      indexingJobService.completeJob(jobId, processed, failed, skipped);
    } catch (IOException e) {
      log.error("Failed to discover files", e);
      indexingJobService.failJob(jobId, e.getMessage());
    } catch (Exception e) {
      log.error("Indexing failed unexpectedly", e);
      indexingJobService.failJob(jobId, e.getMessage());
    }
  }
}
