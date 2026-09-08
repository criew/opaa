package io.opaa.indexing.source.filesystem;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.document.DocumentIngest;
import io.opaa.indexing.document.DocumentIngestResult;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.document.DocumentService;
import io.opaa.indexing.format.SupportedDocumentFormats;
import io.opaa.indexing.job.IndexingEventCategory;
import io.opaa.indexing.job.RejectedDocumentReporter;
import io.opaa.indexing.source.IndexingRun;
import io.opaa.indexing.source.IndexingRunFailedException;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.IndexingSourceType;
import io.opaa.indexing.source.ListingOutcome;
import io.opaa.indexing.source.ReconcilingAttachmentAccess;
import io.opaa.indexing.source.SourceFolderMirror;
import io.opaa.indexing.source.SourceFolderPath;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryFolderService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;

/**
 * Executes indexing runs for {@link IndexingSourceType#FILESYSTEM} (ADR-0017) over the library's
 * own {@link KnowledgeLibrary#getSourcePath()} (ADR-0018). Every discovered file's directory is
 * mirrored into {@code library_folders} (ADR-0020) before the file is processed.
 *
 * <p>The listing is always complete: every physically found file - indexable or not - is present,
 * so the run frame's reconciliation removes what was not rediscovered, and only then are the
 * folders pruned, so a folder emptied by that cleanup is pruned in the same run. A missing or
 * non-directory {@code sourcePath} fails the run instead of reporting an empty, "successful"
 * bestand.
 */
public class AsyncIndexingExecutor implements SourceIndexingExecutor {

  private static final Logger log = LoggerFactory.getLogger(AsyncIndexingExecutor.class);

  private final DocumentService documentService;
  private final DocumentIngestService documentIngestService;
  private final FilesystemPathAllowlist filesystemAllowlist;
  private final LibraryFolderService folderService;
  private final IndexingRunTemplate runTemplate;
  private final SupportedDocumentFormats supportedFormats;

  public AsyncIndexingExecutor(
      DocumentService documentService,
      DocumentIngestService documentIngestService,
      FilesystemPathAllowlist filesystemAllowlist,
      LibraryFolderService folderService,
      IndexingRunTemplate runTemplate,
      SupportedDocumentFormats supportedFormats) {
    this.documentService = documentService;
    this.documentIngestService = documentIngestService;
    this.filesystemAllowlist = filesystemAllowlist;
    this.folderService = folderService;
    this.runTemplate = runTemplate;
    this.supportedFormats = supportedFormats;
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
    runTemplate.run(jobId, targetLibrary, runMode, this, this::indexDirectory);
  }

  private ListingOutcome indexDirectory(IndexingRun run) throws IOException, InterruptedException {
    KnowledgeLibrary targetLibrary = run.library();
    // ADR-0018 Entscheidung 6: re-checked at run time, not only at library creation/update time -
    // the operator-configured allowlist can be narrowed after a FILESYSTEM library was created.
    if (!filesystemAllowlist.isAllowed(targetLibrary.getSourcePath())) {
      log.warn(
          "Refusing to index library {}: sourcePath {} is outside the configured filesystem"
              + " allowlist",
          targetLibrary.getId(),
          targetLibrary.getSourcePath());
      run.events()
          .record(
              IndexingEventCategory.ALLOWLIST,
              "Verzeichnispfad liegt außerhalb der vom Betrieb freigegebenen Verzeichnisse",
              targetLibrary.getSourcePath());
      throw new IndexingRunFailedException(
          "sourcePath liegt außerhalb der vom Betrieb freigegebenen Verzeichnisse - der Lauf"
              + " wurde nicht gestartet");
    }

    // normalize()/toAbsolutePath(): every file's own file.toAbsolutePath().toString() is its
    // document key, so a sourcePath that is not in canonical form would re-key the library's
    // documents on every run.
    Path documentDir = Path.of(targetLibrary.getSourcePath()).toAbsolutePath().normalize();
    DocumentService.DiscoveredFiles discovered =
        documentService.discoverFiles(documentDir, supportedFormats);
    List<Path> files = discovered.supported();
    log.info(
        "Discovered {} files in {}, {} of them indexable",
        discovered.totalFound(),
        documentDir,
        files.size());

    // Rejected documents are part of the job, not invisible: they count towards the total, are
    // reported as skipped, and each one becomes its own UNSUPPORTED_FORMAT event.
    for (Path rejected : discovered.rejected()) {
      run.events()
          .record(
              IndexingEventCategory.UNSUPPORTED_FORMAT,
              "Dateiformat wird nicht unterstützt",
              rejected.getFileName().toString());
    }
    run.progress()
        .addSkipped(
            RejectedDocumentReporter.reportRejected(
                IndexingSourceType.FILESYSTEM,
                documentDir.toString(),
                discovered.rejected().stream().map(p -> p.getFileName().toString()).toList(),
                supportedFormats));

    // A file whose own extension does not match its detected content is still indexed - only
    // reported, never rejected or silently reinterpreted.
    for (DocumentService.FormatMismatch mismatch : discovered.mismatches()) {
      run.events()
          .record(
              IndexingEventCategory.FORMAT_MISMATCH,
              "Dateiendung passt nicht zum erkannten Inhalt (erkannt: "
                  + mismatch.detectedExtension()
                  + ")",
              mismatch.file().getFileName().toString());
    }

    run.progress().setTotal(discovered.totalFound());
    run.progress().report();

    ReconcilingAttachmentAccess attachmentAccess = run.attachmentAccess();
    var folderMirror = new SourceFolderMirror(folderService, targetLibrary);

    for (Path file : files) {
      String fileName = file.getFileName().toString();
      String filePath = file.toAbsolutePath().toString();
      run.markPresent(filePath);
      try {
        log.info("Processing: {}", fileName);
        UUID folderId = materializeFolder(documentDir, file, folderMirror);
        folderMirror.markSeen(folderId);
        DocumentIngestResult result =
            documentIngestService.ingest(
                DocumentIngest.localFile(targetLibrary, file).folder(folderId).build(),
                attachmentAccess);
        if (run.recordOutcome(result, fileName)) {
          run.markReprocessed(filePath);
          log.info("Indexing completed: {}", fileName);
        }
      } catch (Exception e) {
        IndexingRun.rethrowRunEnding(e);
        run.recordFailure(fileName, e);
      }
      run.progress().report();
    }
    // "physically found", not "indexable": an unsupported-format file is still present at the
    // source and must not be treated as vanished.
    for (Path rejected : discovered.rejected()) {
      run.markPresent(rejected.toAbsolutePath().toString());
    }
    run.afterReconciliation(reconciled -> folderMirror.prune());
    return ListingOutcome.complete();
  }

  /**
   * Resolves the {@code io.opaa.library.LibraryFolder} {@code file}'s own directory maps to under
   * {@code documentDir}, materializing it through {@link SourceFolderMirror} - which caches per
   * distinct directory, so a directory holding thousands of files still costs one materialization.
   *
   * <p>{@code documentDir} and {@code file} are both already absolute and {@link Path#normalize()
   * normalize}d - {@code file} because {@link DocumentService#discoverFiles} only ever returns
   * entries {@link java.nio.file.Files#walk} found physically under {@code documentDir} (walked
   * without {@code FOLLOW_LINKS} - a symlink is a leaf, never traversed into), so a defensive
   * {@link Path#startsWith} guard is enough to catch an unexpected escape rather than needing to
   * resolve symlinks up front.
   *
   * @return {@code null} for a file directly in {@code documentDir} (the library's root), when
   *     {@code file} unexpectedly does not sit under {@code documentDir} at all, or when a
   *     directory name cannot be a folder row ({@link SourceFolderPath#rejects})
   */
  private UUID materializeFolder(Path documentDir, Path file, SourceFolderMirror folderMirror) {
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
    List<String> segments = new ArrayList<>();
    for (Path part : relativeDir) {
      segments.add(part.toString());
    }
    // uncapped: a real directory tree is free to nest deeper than the folder limit
    SourceFolderPath path = SourceFolderPath.of(segments);
    if (path.rejected()) {
      log.warn(
          "Cannot map directory segment \"{}\" of {} to a folder name - leaving the document at"
              + " the library root",
          path.rejectedSegment(),
          normalizedFile);
      return null;
    }
    return folderMirror.folderFor(path.segments());
  }
}
