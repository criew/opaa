package io.opaa.indexing.source.filesystem;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
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
import io.opaa.indexing.RejectedDocumentReporter;
import io.opaa.indexing.StaleDocumentCleanupService;
import io.opaa.indexing.source.IndexingSourceType;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.VanishedDocumentPolicy;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
 * read-only counterpart to the CRUD-managed folders of an {@code UPLOAD} library. Once every
 * discovered file has been processed, {@link StaleDocumentCleanupService#cleanupVanished} removes
 * every {@code FILESYSTEM} document of this library whose path was not rediscovered - it no longer
 * exists under {@code sourcePath} (#886) - and only then does {@link
 * LibraryFolderService#pruneOrphanedFolders} remove any folder this run never touched and that
 * holds no document, directly or transitively: that order lets a folder emptied by the cleanup
 * above be pruned in this same run instead of lagging one run behind. Both are only reached on this
 * method's own success path (never from a {@code catch} block), so a failed or crashed run never
 * deletes anything; {@code discoverFiles} walks the whole tree with no truncation limit, so -
 * unlike {@code UrlIndexingExecutor} - there is no capped-run case to guard against here. It does,
 * however, throw when {@code sourcePath} itself does not currently exist or is not a directory
 * (#886 review) - an unmounted network share or a moved directory fails this run instead of
 * silently reporting an empty, "successful" bestand that {@link
 * StaleDocumentCleanupService#cleanupVanished} would otherwise read as "every document vanished".
 * {@code cleanupVanished}'s own {@code currentFilePaths} is built from every physically found file,
 * not only the indexable ones - an unreadable file ({@link
 * DocumentService.DiscoveredFiles#rejected}) is still present at the source, just not indexable,
 * and must not be treated as vanished either.
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
  private final StaleDocumentCleanupService staleDocumentCleanupService;
  private final DocumentRepository documentRepository;

  public AsyncIndexingExecutor(
      DocumentService documentService,
      FileProcessingService fileProcessingService,
      IndexingJobService indexingJobService,
      FilesystemPathAllowlist filesystemAllowlist,
      IndexingRunEventRepository indexingRunEventRepository,
      LibraryStorageQuotaService storageQuotaService,
      LibraryFolderService folderService,
      StaleDocumentCleanupService staleDocumentCleanupService,
      DocumentRepository documentRepository) {
    this.documentService = documentService;
    this.fileProcessingService = fileProcessingService;
    this.indexingJobService = indexingJobService;
    this.filesystemAllowlist = filesystemAllowlist;
    this.indexingRunEventRepository = indexingRunEventRepository;
    this.storageQuotaService = storageQuotaService;
    this.folderService = folderService;
    this.staleDocumentCleanupService = staleDocumentCleanupService;
    this.documentRepository = documentRepository;
  }

  @Override
  public IndexingSourceType sourceType() {
    return IndexingSourceType.FILESYSTEM;
  }

  @Override
  public Map<IndexingRunMode, VanishedDocumentPolicy> runModes() {
    // ADR-0023, Entscheidung 4: one mode only, "vollständig auflistend".
    return Map.of(IndexingRunMode.FULL, VanishedDocumentPolicy.REMOVE_ON_ABSENCE);
  }

  @Override
  @Async("indexingTaskExecutor")
  public void execute(UUID jobId, KnowledgeLibrary targetLibrary, IndexingRunMode runMode) {
    var progress = new IndexingRunProgress(indexingJobService, jobId);
    var events =
        new IndexingRunEventRecorder(indexingRunEventRepository, indexingJobService, jobId);
    if (!runModes().containsKey(runMode)) {
      progress.fail("Betriebsart " + runMode + " wird für diesen Quellentyp nicht unterstützt");
      return;
    }

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

      // What the attachment path created or confirmed this run, and which of those were actually
      // re-parsed - feeds the vanished-cleanup bookkeeping below (ADR-0022, Entscheidung 3).
      Set<String> indexedAttachmentPaths = new HashSet<>();
      Set<String> reprocessedAttachmentPaths = new HashSet<>();
      var attachmentAccess =
          new FilesystemAttachmentAccess(
              targetLibrary, events, progress, indexedAttachmentPaths, reprocessedAttachmentPaths);
      // Files whose content was actually (re-)parsed this run - their attachment set was freshly
      // enumerated, so only the attachment paths recorded above count for them.
      Set<String> reprocessedFilePaths = new HashSet<>();

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
              fileProcessingService.processFile(file, targetLibrary, folderId, attachmentAccess);
          if (result == FileProcessingResult.QUOTA_EXCEEDED) {
            // The library's storage quota was reached mid-run - the file is skipped, not treated
            // as an error, and the reason is recorded so an operator can see why the bestand
            // stopped growing.
            events.record(
                IndexingEventCategory.REJECTED,
                storageQuotaService.quotaExceededMessage(targetLibrary.getId()),
                fileName);
            progress.recordSkipped();
          } else if (result == FileProcessingResult.NO_EXTRACTABLE_TEXT) {
            // See io.opaa.indexing.pipeline.TikaFallbackPipeline#isTextlessPdf and
            // FileProcessingResult#NO_EXTRACTABLE_TEXT: the
            // document was already rejected and marked FAILED with the user-facing message below -
            // reported the same way QUOTA_EXCEEDED is, not silently counted as processed.
            events.record(
                IndexingEventCategory.REJECTED,
                DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE,
                fileName);
            progress.recordSkipped();
          } else if (result == FileProcessingResult.FAILED) {
            // See DocumentPipelineResult.Outcome#NO_CONTENT: the pipeline could not parse the
            // document at all - the same failure the catch block below reports, only reached
            // without throwing (#1108).
            events.record(IndexingEventCategory.ERROR, "Verarbeitung fehlgeschlagen", fileName);
            progress.recordFailed();
          } else if (result == FileProcessingResult.SKIPPED) {
            progress.recordSkipped();
          } else {
            reprocessedFilePaths.add(file.toAbsolutePath().toString());
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

      // Reached only once every discovered file has been accounted for above - see this class'
      // own Javadoc on why that makes this call safe (#886). Runs before pruneOrphanedFolders
      // below so a folder that only held a now-vanished document can already be pruned in this
      // same run, instead of lagging one run behind.
      try {
        // #886 review: "physically found", not "indexable" - an unreadable/unsupported-format
        // file is still present at the source and must not be treated as vanished.
        Set<String> currentFilePaths =
            Stream.concat(files.stream(), discovered.rejected().stream())
                .map(f -> f.toAbsolutePath().toString())
                .collect(Collectors.toCollection(HashSet::new));
        // ADR-0022, Entscheidung 3: an attachment counts as present this run either because the
        // attachment path itself created/confirmed it while its parent was re-parsed
        // (indexedAttachmentPaths), or - the Nachtragsfall - because its parent still exists but
        // was NOT re-parsed this run (checksum-skipped, rejected, failed), so its existing
        // attachment rows must be preserved from the database. An attachment of a re-parsed
        // parent that was NOT re-reported is genuinely gone (removed from the mail) and is
        // deliberately not folded in, so cleanupVanished below removes it.
        currentFilePaths.addAll(indexedAttachmentPaths);
        Set<String> reprocessedPaths = new HashSet<>(reprocessedFilePaths);
        reprocessedPaths.addAll(reprocessedAttachmentPaths);
        List<Document> existingFilesystemDocuments =
            documentRepository.findByLibraryIdAndSourceType(
                targetLibrary.getId(), DocumentSourceType.FILESYSTEM);
        StaleDocumentCleanupService.foldInPreservedAttachmentPaths(
            existingFilesystemDocuments, currentFilePaths, reprocessedPaths);
        staleDocumentCleanupService.cleanupVanished(
            targetLibrary, DocumentSourceType.FILESYSTEM, currentFilePaths, events, this, runMode);
      } catch (Exception e) {
        log.warn(
            "Failed to clean up vanished FILESYSTEM documents for library {}",
            targetLibrary.getId(),
            e);
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
