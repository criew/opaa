package io.opaa.indexing.source.s3;

import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.filesync.FileEntry;
import io.opaa.indexing.filesync.FileReference;
import io.opaa.indexing.filesync.FileSync;
import io.opaa.indexing.filesync.FileSyncSettings;
import io.opaa.indexing.filesync.FileSyncWording;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.source.IndexingRun;
import io.opaa.indexing.source.ListingOutcome;
import io.opaa.indexing.source.SourceSyncState;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.SourceDocumentContext;
import io.opaa.s3.S3AccessException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The full sync and the event run of an S3 library (ADR-0027, Entscheidungen 3 to 6) on the shared
 * {@link FileSync}: this class supplies the store as {@link S3FileStore}, the S3 wording and the
 * bounds of {@link S3Properties}, and resolves reported {@code bucket/key} references to scopes.
 * Must be {@link #close() closed} with the run.
 */
final class S3FullSync implements AutoCloseable, FileSyncWording {

  static final String UNLISTABLE_SCOPE_SUFFIX = FileSync.UNLISTABLE_CONTAINER_SUFFIX;
  static final String UNREADABLE_OBJECT_SUFFIX = FileSync.UNREADABLE_SUFFIX;
  static final String RECONCILIATION_FAILED_MESSAGE = FileSync.RECONCILIATION_FAILED_MESSAGE;
  static final String FOLDER_MARKERS_SUFFIX =
      " Ordnermarker (Schlüssel endet auf „/“) übersprungen; sie sind keine Dokumente";
  static final String EXCLUDED_KEYS_SUFFIX =
      " Schlüssel durch die Ein-/Ausschlussmuster ausgeschlossen; sie sind nicht Teil des Bestands"
          + " und werden entfernt, falls ein früherer Lauf sie aufgenommen hat";
  static final String GONE_CONFIRMED_MESSAGE =
      "Vom Objektspeicher als gelöscht bestätigt, entfernt";
  static final String DROPPED_EVENTS_SUFFIX =
      " gemeldete Objekte liegen außerhalb der Geltungsbereiche oder Muster und wurden verworfen";

  private final List<S3Scope> scopes;
  private final FileSync sync;

  S3FullSync(
      IndexingRun frame,
      S3FileStore store,
      S3SourceSettings settings,
      S3Properties properties,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService folderService,
      StaleDocumentCleanupService cleanupService,
      SourceSyncState state,
      SourceSyncStateRepository syncStateRepository,
      Clock clock,
      SupportedDocumentFormats supportedFormats) {
    this.scopes = settings.scopes();
    this.sync =
        new FileSync(
            frame,
            store,
            new FileSyncSettings(
                properties.maxObjectSizeBytes(),
                properties.maxObjectsPerRun(),
                properties.downloadConcurrency(),
                "s3-download-"),
            this,
            documentIngestService,
            documentRepository,
            folderService,
            cleanupService,
            state,
            syncStateRepository,
            clock,
            supportedFormats);
  }

  ListingOutcome run() throws InterruptedException {
    return sync.run();
  }

  /**
   * The event run (ADR-0027, Entscheidung 6) over the reported {@code bucket/key} references: one
   * {@code HeadObject} each, in key order; a reference outside every scope is skipped.
   */
  ListingOutcome refresh(Set<String> references, int dropped) throws InterruptedException {
    List<FileReference> inScope = new ArrayList<>();
    int outside = 0;
    for (String reference : references.stream().sorted().toList()) {
      int slash = reference.indexOf('/');
      String bucket = slash < 0 ? reference : reference.substring(0, slash);
      String key = slash < 0 ? "" : reference.substring(slash + 1);
      S3Scope scope =
          scopes.stream()
              .filter(s -> s.bucket().equals(bucket) && s.contains(key))
              .findFirst()
              .orElse(null);
      if (scope == null || key.isEmpty()) {
        outside++;
      } else {
        inScope.add(new FileReference(S3FileStore.container(scope), key, filePath(bucket, key)));
      }
    }
    return sync.refresh(inScope, outside, dropped);
  }

  @Override
  public void close() {
    sync.close();
  }

  @Override
  public String tooLarge(FileEntry entry, long maxBytes) {
    String bucket =
        scopes.stream()
            .filter(scope -> scope.key().equals(entry.container().key()))
            .findFirst()
            .orElseThrow()
            .bucket();
    return S3AccessException.ObjectTooLarge.describe(bucket, entry.id(), maxBytes);
  }

  @Override
  public String tooManyEntries(long maxEntries) {
    return "Die Geltungsbereiche dieser Bibliothek listen mehr als "
        + maxEntries
        + " Objekte; so viele verarbeitet ein Lauf nicht. Bitte die Geltungsbereiche enger"
        + " fassen (Präfixe) oder die Bibliothek aufteilen.";
  }

  @Override
  public String goneConfirmed() {
    return GONE_CONFIRMED_MESSAGE;
  }

  @Override
  public String droppedReferencesNote() {
    return DROPPED_EVENTS_SUFFIX;
  }

  @Override
  public String budgetStallAdvice() {
    return "Der Lauf hat kein Objekt neu aufgenommen. Budget anheben oder die Geltungsbereiche"
        + " aufteilen.";
  }

  @Override
  public String eventRunContinuation() {
    return "die übrigen gemeldeten Objekte nimmt der nächste geplante Lauf auf";
  }

  @Override
  public String listedSummary(long listed, long deselected) {
    return listed + " Objekte gelistet, " + deselected + " durch Muster ausgeschlossen, ";
  }

  @Override
  public String checkedSummary(long checked) {
    return checked + " gemeldete Objekte geprüft, ";
  }

  /** {@code s3://<bucket>/<key>}, the key as it is - the identity per library (Entscheidung 5). */
  static String filePath(String bucket, String key) {
    return S3ObjectRef.filePath(bucket, key);
  }

  /**
   * The folder chain of {@code key} below the scope's prefix, joined with {@link
   * SourceDocumentContext#HIERARCHY_SEPARATOR}; {@code null} for a key directly under the prefix.
   * Deliberately not the mirrored folder chain ({@link S3FolderPath}): the hierarchy path is
   * prefix-relative and complete, whatever the number of scopes, the folder limit or a segment no
   * folder row can carry.
   */
  static String hierarchyPath(S3Scope scope, String key) {
    String relative = key.startsWith(scope.prefix()) ? key.substring(scope.prefix().length()) : key;
    int lastSlash = relative.lastIndexOf('/');
    if (lastSlash <= 0) {
      return null;
    }
    List<String> segments = new ArrayList<>();
    for (String segment : relative.substring(0, lastSlash).split("/")) {
      if (!segment.isEmpty()) {
        segments.add(segment);
      }
    }
    return segments.isEmpty()
        ? null
        : String.join(SourceDocumentContext.HIERARCHY_SEPARATOR, segments);
  }
}
