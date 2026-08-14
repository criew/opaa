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
      DocumentService.DiscoveredFiles discovered = documentService.discoverFiles(documentDir);
      List<Path> files = discovered.supported();
      log.info(
          "Discovered {} files in {}, {} of them indexable",
          discovered.totalFound(),
          documentDir,
          files.size());

      // Issue #375: rejected documents are part of the job, not invisible. They count towards the
      // total and are reported as skipped, so nobody has to guess why the number of indexed
      // documents is lower than the number of files in the directory.
      skipped += reportRejected(discovered.rejected());

      indexingJobService.setTotalDocuments(jobId, discovered.totalFound());
      indexingJobService.updateProgress(jobId, processed, failed, skipped);

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

  /** Names every rejected document in the log and returns how many there were. */
  private int reportRejected(List<Path> rejected) {
    if (rejected.isEmpty()) {
      return 0;
    }
    log.warn(
        "Rejected {} document(s) because of an unsupported format (supported: {}): {}",
        rejected.size(),
        SupportedDocumentFormats.extensions(),
        rejected.stream().map(p -> p.getFileName().toString()).toList());
    return rejected.size();
  }
}
