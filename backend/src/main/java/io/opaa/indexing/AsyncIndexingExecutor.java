package io.opaa.indexing;

import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryFolderService;
import io.opaa.library.LibraryStorageQuotaService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

/**
 * Executes indexing runs for {@link IndexingSourceType#FILESYSTEM} (ADR-0017). Since ADR-0018, the
 * directory to crawl is the library's own {@link KnowledgeLibrary#getSourcePath()} - not a single,
 * application-wide path, so different FILESYSTEM libraries can watch different directories.
 *
 * <p>Every discovered file's directory under {@code sourcePath} is mirrored into {@code
 * library_folders} via {@link LibraryFolderService#materializeFolderPath} before it is handed to
 * {@link FileProcessingService#processFile(Path, KnowledgeLibrary, UUID)} (ADR-0020) - the
 * read-only counterpart to the CRUD-managed folders of an {@code UPLOAD} library. Once the run's
 * own discovery has finished, {@link LibraryFolderService#pruneOrphanedFolders} removes any folder
 * this run never touched and that holds no document, directly or transitively.
 */
public class AsyncIndexingExecutor implements SourceIndexingExecutor {

  private static final Logger log = LoggerFactory.getLogger(AsyncIndexingExecutor.class);

  private final DocumentService documentService;
  private final FileProcessingService fileProcessingService;
  private final IndexingJobService indexingJobService;
  private final FilesystemPathAllowlist filesystemAllowlist;
  private final IndexingRunEventRepository indexingRunEventRepository;
  private final LibraryStorageQuotaService storageQuotaService;
  private final LibraryFolderService folderService;

  public AsyncIndexingExecutor(
      DocumentService documentService,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      FilesystemPathAllowlist filesystemAllowlist,
      IndexingRunEventRepository indexingRunEventRepository,
      LibraryStorageQuotaService storageQuotaService,
      LibraryFolderService folderService) {
    this.documentService = documentService;
    this.fileProcessingService = fileProcessingService;
    this.indexingJobService = indexingJobService;
    this.filesystemAllowlist = filesystemAllowlist;
    this.indexingRunEventRepository = indexingRunEventRepository;
    this.storageQuotaService = storageQuotaService;
    this.folderService = folderService;
  }

  @Override
  public IndexingSourceType sourceType() {
    return IndexingSourceType.FILESYSTEM;
  }

  @Override
  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary) {
    var progress = new IndexingRunProgress(indexingJobService, jobId);
    var events =
        new IndexingRunEventRecorder(indexingRunEventRepository, indexingJobService, jobId);

    // ADR-0018 Entscheidung 6: re-checked at run time, not only at library creation/update time -
    // the operator-configured allowlist can be narrowed after a FILESYSTEM library was created. The
    // job is started (see DocumentIndexingService) before this executor ever runs, so rejecting it
    // here means the job, not the trigger, FAILED.
    if (!filesystemAllowlist.isAllowed(targetLibrary.getSourcePath())) {
      log.warn(
          "Refusing to index library {}: sourcePath {} is outside the configured filesystem"
              + " allowlist",
          targetLibrary.getId(),
          targetLibrary.getSourcePath());
      events.record(
          IndexingEventCategory.ALLOWLIST,
          "Verzeichnispfad liegt außerhalb der vom Betrieb freigegebenen Verzeichnisse",
          targetLibrary.getSourcePath());
      progress.fail(
          "sourcePath liegt außerhalb der vom Betrieb freigegebenen Verzeichnisse - der Lauf"
              + " wurde nicht gestartet");
      return;
    }

    try {
      // normalize()/toAbsolutePath(): a sourcePath that is not already in canonical form (contains
      // "." / ".." segments, or is relative to the process working directory) produces a different
      // documentDir string, which changes every file's own file.toAbsolutePath().toString() key
      // (FileProcessingService#processFile's documentRepository.findByLibraryIdAndFilePath lookup)
      // the same way. A library whose sourcePath was never in canonical form re-keys its documents
      // exactly once, the next time it is indexed after this change - a normal re-index, not data
      // loss.
      Path documentDir = Path.of(targetLibrary.getSourcePath()).toAbsolutePath().normalize();
      DocumentService.DiscoveredFiles discovered = documentService.discoverFiles(documentDir);
      List<Path> files = discovered.supported();
      log.info(
          "Discovered {} files in {}, {} of them indexable",
          discovered.totalFound(),
          documentDir,
          files.size());

      // Rejected documents are part of the job, not invisible. They count towards the total and
      // are reported as skipped, and each one also becomes its own UNSUPPORTED_FORMAT event, so
      // the reason is visible per file.
      for (Path rejected : discovered.rejected()) {
        events.record(
            IndexingEventCategory.UNSUPPORTED_FORMAT,
            "Dateiformat wird nicht unterstützt",
            rejected.getFileName().toString());
      }
      progress.addSkipped(
          RejectedDocumentReporter.reportRejected(
              IndexingSourceType.FILESYSTEM,
              documentDir.toString(),
              discovered.rejected().stream().map(p -> p.getFileName().toString()).toList()));

      // A file whose own extension does not match its detected content is still indexed - only
      // reported, never rejected or silently reinterpreted.
      for (DocumentService.FormatMismatch mismatch : discovered.mismatches()) {
        events.record(
            IndexingEventCategory.FORMAT_MISMATCH,
            "Dateiendung passt nicht zum erkannten Inhalt (erkannt: "
                + mismatch.detectedExtension()
                + ")",
            mismatch.file().getFileName().toString());
      }

      progress.setTotal(discovered.totalFound());
      progress.report();

      // The set of folders this run actually materialized/touched - everything else under this
      // library once the loop below finishes is a candidate for pruneOrphanedFolders.
      Set<UUID> seenFolderIds = new HashSet<>();
      // A large tree can hold thousands of files per directory - without this cache, every one of
      // them would call materializeFolderPath (a SELECT per path segment) for a relative directory
      // this run has already resolved moments ago. Keyed by the relative directory Path (null for
      // the library's root, mirroring materializeFolder's own convention below).
      Map<Path, UUID> folderIdByRelativeDir = new HashMap<>();

      for (Path file : files) {
        String fileName = file.getFileName().toString();
        try {
          log.info("Processing: {}", fileName);
          UUID folderId =
              materializeFolder(documentDir, file, targetLibrary, folderIdByRelativeDir);
          if (folderId != null) {
            seenFolderIds.add(folderId);
          }
          FileProcessingResult result =
              fileProcessingService.processFile(file, targetLibrary, folderId);
          if (result == FileProcessingResult.QUOTA_EXCEEDED) {
            // The library's storage quota was reached mid-run - the file is skipped, not treated
            // as an error, and the reason is recorded so an operator can see why the bestand
            // stopped growing.
            events.record(
                IndexingEventCategory.REJECTED,
                storageQuotaService.quotaExceededMessage(targetLibrary.getId()),
                fileName);
            progress.recordSkipped();
          } else if (result == FileProcessingResult.SKIPPED) {
            progress.recordSkipped();
          } else {
            progress.recordProcessed();
            log.info("Indexing completed: {}", fileName);
          }
        } catch (Exception e) {
          log.error("Failed to process file: {}", fileName, e);
          events.record(IndexingEventCategory.ERROR, "Verarbeitung fehlgeschlagen", fileName);
          progress.recordFailed();
        }
        progress.report();
      }

      // Caught separately, not left to the outer catch below - a failure here must not turn an
      // otherwise-successful document run into a FAILED job.
      try {
        folderService.pruneOrphanedFolders(targetLibrary, seenFolderIds);
      } catch (Exception e) {
        log.warn(
            "Failed to prune orphaned filesystem folders for library {}", targetLibrary.getId(), e);
      }

      events.finalizeRun();
      progress.complete();
    } catch (IOException e) {
      log.error("Failed to discover files", e);
      events.finalizeRun();
      progress.fail(e.getMessage());
    } catch (Exception e) {
      log.error("Indexing failed unexpectedly", e);
      events.finalizeRun();
      progress.fail(e.getMessage());
    }
  }

  /**
   * Resolves the {@code io.opaa.library.LibraryFolder} {@code file}'s own directory maps to under
   * {@code documentDir}, materializing it via {@link LibraryFolderService#materializeFolderPath}
   * only on a {@code folderIdByRelativeDir} cache miss - one call per distinct directory this run
   * visits, not one per file; every other file in an already-resolved directory is a plain map
   * lookup.
   *
   * <p>{@code documentDir} and {@code file} are both already absolute and {@link Path#normalize()
   * normalize}d - {@code file} because {@link DocumentService#discoverFiles(Path)} only ever
   * returns entries {@link java.nio.file.Files#walk} found physically under {@code documentDir}
   * (walked without {@code FOLLOW_LINKS} - a symlink is a leaf, never traversed into), so a
   * defensive {@link Path#startsWith} guard is enough to catch an unexpected escape rather than
   * needing to resolve symlinks up front.
   *
   * @return {@code null} for a file directly in {@code documentDir} (the library's root), or when
   *     {@code file} unexpectedly does not sit under {@code documentDir} at all
   */
  private UUID materializeFolder(
      Path documentDir, Path file, KnowledgeLibrary targetLibrary, Map<Path, UUID> folderCache) {
    Path normalizedFile = file.toAbsolutePath().normalize();
    if (!normalizedFile.startsWith(documentDir)) {
      log.warn(
          "File {} does not sit under its library's sourcePath {} after normalization - leaving"
              + " it at the library root",
          normalizedFile,
          documentDir);
      return null;
    }
    Path relativeDir = documentDir.relativize(normalizedFile).getParent();
    if (relativeDir == null) {
      return null;
    }
    if (folderCache.containsKey(relativeDir)) {
      return folderCache.get(relativeDir);
    }
    List<String> segments = new ArrayList<>();
    for (Path part : relativeDir) {
      segments.add(part.toString());
    }
    UUID folderId = folderService.materializeFolderPath(targetLibrary, segments);
    folderCache.put(relativeDir, folderId);
    return folderId;
  }
}
