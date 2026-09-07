package io.opaa.indexing.source.s3;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.DocumentIngest;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.SourceDocumentContext;
import io.opaa.indexing.SupportedDocumentFormats;
import io.opaa.indexing.source.IndexingRun;
import io.opaa.indexing.source.IndexingRunFailedException;
import io.opaa.indexing.source.ListingOutcome;
import io.opaa.indexing.source.ReconcilingAttachmentAccess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One full sync of an S3 library over one open store (ADR-0027, Entscheidung 3): every scope is
 * listed page by page to its last page, every listed object that the patterns admit is marked
 * present whatever its outcome, the change feature (Entscheidung 4) decides before any download,
 * and only a listing that reached the last page of every scope reports {@link
 * ListingOutcome.Complete}. A scope the credentials cannot list leaves its bestand alone and is
 * named in the protocol and the assessment; a spent request budget ends the run truncated; a
 * store-wide failure (credentials, clock, TLS, reachability) fails the run with the access layer's
 * own sentence.
 */
final class S3FullSync {

  private static final Logger log = LoggerFactory.getLogger(S3FullSync.class);

  static final String UNLISTABLE_SCOPE_SUFFIX =
      " Sein Bestand bleibt bis zur nächsten vollständigen Auflistung unverändert.";
  static final String UNREADABLE_OBJECT_SUFFIX = " Der bereits indizierte Stand bleibt erhalten.";
  static final String UNSUPPORTED_FORMAT_MESSAGE = "Dateiformat wird nicht unterstützt";
  static final String GONE_SUFFIX = " Zwischen Auflistung und Abruf entfernt.";
  static final String FOLDER_MARKERS_SUFFIX =
      " Ordnermarker (Schlüssel endet auf „/“) übersprungen; sie sind keine Dokumente";
  static final String EXCLUDED_KEYS_SUFFIX =
      " Schlüssel durch die Ein-/Ausschlussmuster ausgeschlossen; sie sind nicht Teil des Bestands"
          + " und werden entfernt, falls ein früherer Lauf sie aufgenommen hat";

  private final IndexingRun frame;
  private final S3ObjectStore store;
  private final S3Properties properties;
  private final FileProcessingService fileProcessingService;
  private final S3KeyPatterns patterns;

  /** The scopes behind an incomplete listing as {@code bucket/prefix}, in the order met. */
  private final Set<String> unlistableScopeKeys = new LinkedHashSet<>();

  private int total;
  private long listed;
  private long folderMarkers;
  private long excludedKeys;

  S3FullSync(
      IndexingRun frame,
      S3ObjectStore store,
      S3SourceSettings settings,
      S3Properties properties,
      FileProcessingService fileProcessingService) {
    this.frame = frame;
    this.store = store;
    this.properties = properties;
    this.fileProcessingService = fileProcessingService;
    this.patterns = S3KeyPatterns.of(settings);
  }

  ListingOutcome run(List<S3Scope> scopes) throws InterruptedException {
    try {
      for (S3Scope scope : scopes) {
        listScope(scope);
      }
    } catch (S3AccessException.BudgetExhausted e) {
      recordSummaries();
      return recordBudgetExhausted(e);
    }
    recordSummaries();
    if (!unlistableScopeKeys.isEmpty()) {
      log.info(
          "S3 full sync for library {} listed incompletely ({}) - keeping the bestand, no"
              + " reconciliation",
          frame.library().getId(),
          unlistableScopeKeys);
      return ListingOutcome.incomplete(List.copyOf(unlistableScopeKeys));
    }
    return ListingOutcome.complete();
  }

  private void listScope(S3Scope scope)
      throws S3AccessException.BudgetExhausted, InterruptedException {
    String token = null;
    do {
      S3ListPage page;
      try {
        page = store.listObjects(scope, token);
      } catch (S3AccessException.BudgetExhausted e) {
        throw e;
      } catch (S3AccessException.ListForbidden
          | S3AccessException.BucketNotFound
          | S3AccessException.ListingIncomplete e) {
        // ADR-0027, Entscheidung 3: a scope that cannot be listed is no deletion finding - the
        // run says so, the rest of the selection is still processed, nothing is reconciled. A
        // store that keeps throttling or is unreachable fails the run instead (the catch below).
        log.warn(
            "S3 scope {} not listable for library {}: {}",
            scope.key(),
            frame.library().getId(),
            e.getMessage());
        frame
            .events()
            .record(
                IndexingEventCategory.REJECTED,
                "Geltungsbereich „"
                    + scope.key()
                    + "“: "
                    + e.getMessage()
                    + UNLISTABLE_SCOPE_SUFFIX,
                scope.key());
        unlistableScopeKeys.add(scope.key());
        return;
      } catch (S3AccessException e) {
        throw new IndexingRunFailedException(e.getMessage(), e);
      }
      listed += page.objects().size();
      if (listed > properties.maxObjectsPerRun()) {
        throw new IndexingRunFailedException(tooManyObjectsMessage());
      }
      List<S3ObjectSummary> admitted = new ArrayList<>();
      for (S3ObjectSummary object : page.objects()) {
        if (patterns.admits(object.key())) {
          admitted.add(object);
        } else {
          excludedKeys++;
        }
      }
      total += admitted.size();
      frame.progress().setTotal(total);
      frame.progress().report();
      for (S3ObjectSummary object : admitted) {
        visitObject(scope, object);
      }
      token = page.nextContinuationToken();
    } while (token != null);
  }

  /**
   * One listed object: present from the first look, then skipped for what the listing already shows
   * (a folder marker, an archive class, an oversize, an unsupported extension), skipped without a
   * download when the change feature is already stored, otherwise fetched and handed to the
   * document path. An extension-less key costs one {@code HeadObject} whose content type decides
   * (Entscheidung 5).
   */
  private void visitObject(S3Scope scope, S3ObjectSummary object)
      throws S3AccessException.BudgetExhausted, InterruptedException {
    String bucket = scope.bucket();
    String key = object.key();
    String filePath = filePath(bucket, key);
    frame.markPresent(filePath);
    String fileName = object.fileName();
    if (object.isFolderMarker() || fileName.isEmpty()) {
      // one summary note per run instead of one entry per marker: a console-created bucket
      // carries a marker per folder, and the protocol holds 500 entries
      folderMarkers++;
      frame.progress().recordSkipped();
      return;
    }
    if (object.isArchived()) {
      skip(
          IndexingEventCategory.REJECTED,
          archivedMessage(filePath, object.storageClass()),
          filePath);
      return;
    }
    if (object.size() > properties.maxObjectSizeBytes()) {
      skip(
          IndexingEventCategory.REJECTED,
          S3AccessException.ObjectTooLarge.describe(bucket, key, properties.maxObjectSizeBytes()),
          filePath);
      return;
    }
    boolean supportedByName = SupportedDocumentFormats.isSupported(fileName);
    if (!supportedByName && hasExtension(fileName)) {
      skip(IndexingEventCategory.UNSUPPORTED_FORMAT, UNSUPPORTED_FORMAT_MESSAGE, filePath);
      return;
    }
    String marker = S3ChangeMarker.of(object);
    if (marker != null && frame.isUnchanged(filePath, marker)) {
      log.debug("Skipping unchanged S3 object: {}", filePath);
      frame.progress().recordSkipped();
      return;
    }
    if (!supportedByName && !headAdmits(bucket, key, filePath)) {
      return;
    }
    download(scope, object, filePath, fileName, marker);
    frame.progress().report();
  }

  /** The {@code HeadObject} of an extension-less key: admitted when its content type is. */
  private boolean headAdmits(String bucket, String key, String filePath)
      throws S3AccessException.BudgetExhausted, InterruptedException {
    S3ObjectHead head;
    try {
      head = store.headObject(bucket, key);
    } catch (S3AccessException e) {
      return handleObjectFailure(filePath, e);
    }
    if (head.archived()) {
      skip(
          IndexingEventCategory.REJECTED, archivedMessage(filePath, head.storageClass()), filePath);
      return false;
    }
    if (SupportedDocumentFormats.extensionForContentType(head.contentType()) == null) {
      skip(
          IndexingEventCategory.UNSUPPORTED_FORMAT,
          UNSUPPORTED_FORMAT_MESSAGE
              + " (Content-Type "
              + (head.contentType() == null ? "unbekannt" : head.contentType())
              + ")",
          filePath);
      return false;
    }
    return true;
  }

  private void download(
      S3Scope scope, S3ObjectSummary object, String filePath, String fileName, String marker)
      throws S3AccessException.BudgetExhausted, InterruptedException {
    S3Download download;
    try {
      download = store.getObject(scope.bucket(), object.key(), properties.maxObjectSizeBytes());
    } catch (S3AccessException e) {
      handleObjectFailure(filePath, e);
      return;
    }
    Path file = download.file();
    try {
      String changeMarker =
          marker != null
              ? marker
              : S3ChangeMarker.of(download.eTag(), download.size(), download.lastModified());
      SourceDocumentContext context =
          new SourceDocumentContext(scope.bucket(), hierarchyPath(scope, object.key()));
      ReconcilingAttachmentAccess attachmentAccess = frame.attachmentAccess(context);
      FileProcessingResult result =
          fileProcessingService.ingest(
              DocumentIngest.builder(frame.library())
                  .file(file, download.size())
                  .filePath(filePath)
                  .fileName(fileName)
                  .sourceType(DocumentSourceType.S3)
                  .context(context)
                  .changeMarker(changeMarker)
                  .build(),
              attachmentAccess);
      if (frame.recordOutcome(result, filePath)) {
        frame.markReprocessed(filePath);
        log.info("Indexed S3 object: {}", filePath);
      } else if (result == FileProcessingResult.SKIPPED) {
        // a new feature over the same bytes (multipart re-upload, re-encryption): the row keeps
        // its id and chunks, only the feature was refreshed
        log.info(
            "S3 object {} changed its feature but not its checksum, provenance refreshed",
            filePath);
      }
    } catch (Exception e) {
      frame.recordFailure(filePath, e);
    } finally {
      try {
        Files.deleteIfExists(file);
      } catch (IOException e) {
        log.warn("Failed to delete temp file: {}", file, e);
      }
    }
  }

  /**
   * What one object's failed head or download means: a missing object is the positive finding a
   * deletion needs (Entscheidung 3) and leaves the reconciliation set, a refused read or an archive
   * state keeps the stored version, a spent budget ends the run, a store-wide failure (credentials,
   * clock, TLS, blocked target, wrong region, unreachable) fails it, and anything else - a throttle
   * that outlasted its retries, an unexpected answer for this one object - counts as failed while
   * the run goes on.
   *
   * @return always {@code false}: the object is not admitted to a download
   */
  private boolean handleObjectFailure(String filePath, S3AccessException e)
      throws S3AccessException.BudgetExhausted {
    switch (e) {
      case S3AccessException.BudgetExhausted budget -> throw budget;
      case S3AccessException.ObjectNotFound gone -> {
        frame.markAbsent(filePath);
        skip(IndexingEventCategory.REJECTED, gone.getMessage() + GONE_SUFFIX, filePath);
      }
      case S3AccessException.ReadForbidden forbidden ->
          skip(
              IndexingEventCategory.REJECTED,
              forbidden.getMessage() + UNREADABLE_OBJECT_SUFFIX,
              filePath);
      case S3AccessException.Archived archived ->
          skip(IndexingEventCategory.REJECTED, archived.getMessage(), filePath);
      case S3AccessException.ObjectTooLarge tooLarge ->
          skip(IndexingEventCategory.REJECTED, tooLarge.getMessage(), filePath);
      default -> {
        if (failsTheRun(e)) {
          throw new IndexingRunFailedException(e.getMessage(), e);
        }
        frame.events().record(IndexingEventCategory.UNREACHABLE, e.getMessage(), filePath);
        frame.progress().recordFailed();
      }
    }
    return false;
  }

  /** A failure that no later object of this run will do better with. */
  private static boolean failsTheRun(S3AccessException e) {
    return e instanceof S3AccessException.Authentication
        || e instanceof S3AccessException.ClockSkew
        || e instanceof S3AccessException.Tls
        || e instanceof S3AccessException.TargetBlocked
        || e instanceof S3AccessException.WrongRegionOrStyle
        || e instanceof S3AccessException.Unreachable;
  }

  private void skip(IndexingEventCategory category, String message, String filePath) {
    frame.events().record(category, message, filePath);
    frame.progress().recordSkipped();
  }

  /** One note per run for what was skipped in bulk - the protocol holds 500 entries. */
  private void recordSummaries() {
    if (folderMarkers > 0) {
      frame
          .events()
          .record(
              IndexingEventCategory.UNSUPPORTED_FORMAT,
              folderMarkers + FOLDER_MARKERS_SUFFIX,
              null);
    }
    if (excludedKeys > 0) {
      frame
          .events()
          .record(IndexingEventCategory.REJECTED, excludedKeys + EXCLUDED_KEYS_SUFFIX, null);
    }
  }

  private ListingOutcome recordBudgetExhausted(S3AccessException.BudgetExhausted e) {
    frame
        .events()
        .record(
            IndexingEventCategory.BUDGET_EXHAUSTED,
            "Anfragebudget von "
                + e.budget()
                + " Anfragen erschöpft; der Lauf endet unvollständig, der nächste Lauf listet"
                + " alle Geltungsbereiche erneut und lädt nur, was noch fehlt"
                + (unlistableScopeKeys.isEmpty()
                    ? ""
                    : "; bis dahin nicht auflistbar: " + String.join(", ", unlistableScopeKeys)),
            null);
    if (frame.progress().processedCount() == 0 && frame.progress().attachmentsProcessed() == 0) {
      // a run that stored nothing new will not do better next time - the chain has stalled
      frame
          .events()
          .record(
              IndexingEventCategory.ERROR,
              "Das Anfragebudget von "
                  + e.budget()
                  + " Anfragen reicht für diese Bibliothek nicht aus: Der Lauf hat kein Objekt"
                  + " neu aufgenommen. Budget anheben oder die Geltungsbereiche aufteilen.",
              null);
    }
    return ListingOutcome.truncated();
  }

  private String tooManyObjectsMessage() {
    return "Die Geltungsbereiche dieser Bibliothek listen mehr als "
        + properties.maxObjectsPerRun()
        + " Objekte; so viele verarbeitet ein Lauf nicht. Bitte die Geltungsbereiche enger"
        + " fassen (Präfixe) oder die Bibliothek aufteilen.";
  }

  private static String archivedMessage(String filePath, String storageClass) {
    return "Das Objekt „"
        + filePath.substring("s3://".length())
        + "“ liegt in der Archivklasse "
        + (storageClass == null ? "(unbekannt)" : storageClass)
        + " und ist ohne Wiederherstellung nicht lesbar.";
  }

  /** {@code s3://<bucket>/<key>}, the key as it is - the identity per library (Entscheidung 5). */
  static String filePath(String bucket, String key) {
    return "s3://" + bucket + "/" + key;
  }

  private static boolean hasExtension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot > 0 && dot < fileName.length() - 1;
  }

  /**
   * The folder chain of {@code key} below the scope's prefix, joined with {@link
   * SourceDocumentContext#HIERARCHY_SEPARATOR}; {@code null} for a key directly under the prefix.
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
