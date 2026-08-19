package io.opaa.indexing;

import io.opaa.library.KnowledgeLibrary;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

/**
 * Executes indexing runs for {@link IndexingSourceType#FILESYSTEM} (ADR-0017). Since ADR-0018
 * (#478), the directory to crawl is the library's own {@link KnowledgeLibrary#getSourcePath()} -
 * not a single, application-wide {@code IndexingProperties#documentPath()} any more, so different
 * FILESYSTEM libraries can watch different directories.
 */
public class AsyncIndexingExecutor implements SourceIndexingExecutor {

  private static final Logger log = LoggerFactory.getLogger(AsyncIndexingExecutor.class);

  private final DocumentService documentService;
  private final FileProcessingService fileProcessingService;
  private final IndexingJobService indexingJobService;
  private final FilesystemPathAllowlist filesystemAllowlist;

  public AsyncIndexingExecutor(
      DocumentService documentService,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      FilesystemPathAllowlist filesystemAllowlist) {
    this.documentService = documentService;
    this.fileProcessingService = fileProcessingService;
    this.indexingJobService = indexingJobService;
    this.filesystemAllowlist = filesystemAllowlist;
  }

  @Override
  public IndexingSourceType sourceType() {
    return IndexingSourceType.FILESYSTEM;
  }

  @Override
  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary) {
    var progress = new IndexingRunProgress(indexingJobService, jobId);

    // #484/ADR-0018 Entscheidung 6: re-checked at run time, not only at library creation/update
    // time - the operator-configured allowlist can be narrowed after a FILESYSTEM library was
    // created, and a Bestandsbibliothek whose sourcePath has since fallen outside it must not run
    // just because it once passed validation. The job is started (see DocumentIndexingService)
    // before this executor ever runs, so rejecting it here means the job, not the trigger,
    // FAILED.
    if (!filesystemAllowlist.isAllowed(targetLibrary.getSourcePath())) {
      log.warn(
          "Refusing to index library {}: sourcePath {} is outside the configured filesystem"
              + " allowlist",
          targetLibrary.getId(),
          targetLibrary.getSourcePath());
      progress.fail(
          "sourcePath liegt ausserhalb der vom Betrieb freigegebenen Verzeichnisse - der Lauf"
              + " wurde nicht gestartet");
      return;
    }

    try {
      Path documentDir = Path.of(targetLibrary.getSourcePath());
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
