package io.opaa.indexing;

import io.opaa.api.dto.IndexingTriggerRequest;
import io.opaa.library.KnowledgeLibrary;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

/** Executes indexing runs for {@link IndexingSourceType#FILESYSTEM} (ADR-0017). */
public class AsyncIndexingExecutor implements SourceIndexingExecutor {

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

  @Override
  public IndexingSourceType sourceType() {
    return IndexingSourceType.FILESYSTEM;
  }

  /**
   * {@code request} is ignored here: a filesystem run always reads from the configured {@code
   * IndexingProperties#documentPath()}, never from a request field.
   */
  @Override
  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, IndexingTriggerRequest request, KnowledgeLibrary targetLibrary) {
    var progress = new IndexingRunProgress(indexingJobService, jobId);

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
      progress.addSkipped(
          RejectedDocumentReporter.reportRejected(
              IndexingSourceType.FILESYSTEM,
              documentDir.toString(),
              discovered.rejected().stream().map(p -> p.getFileName().toString()).toList()));

      progress.setTotal(discovered.totalFound());
      progress.report();

      for (Path file : files) {
        String fileName = file.getFileName().toString();
        try {
          log.info("Processing: {}", fileName);
          FileProcessingResult result = fileProcessingService.processFile(file, targetLibrary);
          if (result == FileProcessingResult.SKIPPED) {
            progress.recordSkipped();
          } else {
            progress.recordProcessed();
            log.info("Indexing completed: {}", fileName);
          }
        } catch (Exception e) {
          log.error("Failed to process file: {}", fileName, e);
          progress.recordFailed();
        }
        progress.report();
      }

      progress.complete();
    } catch (IOException e) {
      log.error("Failed to discover files", e);
      progress.fail(e.getMessage());
    } catch (Exception e) {
      log.error("Indexing failed unexpectedly", e);
      progress.fail(e.getMessage());
    }
  }
}
