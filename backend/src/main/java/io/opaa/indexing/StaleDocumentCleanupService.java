package io.opaa.indexing;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.VanishedDocumentPolicy;
import io.opaa.library.KnowledgeLibrary;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes documents - and their chunks - that a source no longer contains: after a successful run,
 * every document whose {@code filePath} is missing from the run's current paths ({@link
 * #reconcile}, {@link #cleanupVanished}, scoped to one {@code (library, sourceType)} pair), and on
 * a source's own positive finding one document with its attachments ({@link
 * #removeWithAttachments}). Children always go before their parent.
 *
 * <p><b>Callers carry the "successful, uncapped run" invariant.</b> A run that failed, was
 * cancelled or was truncated must never call {@link #reconcile} or {@link #cleanupVanished}; the
 * run frame only does so for a complete listing in a {@link
 * VanishedDocumentPolicy#REMOVE_ON_ABSENCE} mode. An empty set of current paths deletes nothing
 * either - it is indistinguishable from an unreachable source.
 */
public class StaleDocumentCleanupService {

  private static final Logger log = LoggerFactory.getLogger(StaleDocumentCleanupService.class);

  private static final String REMOVED_MESSAGE = "In der Quelle nicht mehr gefunden, entfernt";

  private final DocumentRepository documentRepository;
  private final VectorChunkStore vectorChunkStore;

  public StaleDocumentCleanupService(
      DocumentRepository documentRepository, VectorChunkStore vectorChunkStore) {
    this.documentRepository = documentRepository;
    this.vectorChunkStore = vectorChunkStore;
  }

  /**
   * The reconciliation of one complete run (ADR-0022, Entscheidung 3): the attachments of every
   * parent in {@code currentPaths} that is not in {@code reprocessedPaths} are preserved from the
   * database, then every document not present is removed as in {@link #cleanupVanished}.
   *
   * @return the number of documents removed
   */
  public int reconcile(
      KnowledgeLibrary library,
      DocumentSourceType sourceType,
      Set<String> currentPaths,
      Set<String> reprocessedPaths,
      IndexingRunEventRecorder events,
      SourceIndexingExecutor executor,
      IndexingRunMode runMode) {
    requireRemoveOnAbsence(sourceType, executor, runMode);
    if (currentPaths.isEmpty()) {
      logEmptyBestand(library, sourceType);
      return 0;
    }
    List<Document> existing =
        documentRepository.findByLibraryIdAndSourceType(library.getId(), sourceType);
    Set<String> present = new HashSet<>(currentPaths);
    foldInPreservedAttachmentPaths(existing, present, reprocessedPaths);
    return removeVanished(library, sourceType, present, existing, events);
  }

  /**
   * Deletes every {@code sourceType} document of {@code library} whose {@code filePath} is not in
   * {@code currentFilePaths}, and nothing at all when that set is empty. Chunks go before the row,
   * unlike {@code LibraryDocumentService#deleteDocument}, whose race with a concurrent upload
   * cannot occur here. Each removal is its own {@link IndexingEventCategory#REMOVED} event.
   *
   * @return the number of documents removed
   */
  public int cleanupVanished(
      KnowledgeLibrary library,
      DocumentSourceType sourceType,
      Set<String> currentFilePaths,
      IndexingRunEventRecorder events,
      SourceIndexingExecutor executor,
      IndexingRunMode runMode) {
    requireRemoveOnAbsence(sourceType, executor, runMode);
    if (currentFilePaths.isEmpty()) {
      logEmptyBestand(library, sourceType);
      return 0;
    }
    List<Document> existing =
        documentRepository.findByLibraryIdAndSourceType(library.getId(), sourceType);
    return removeVanished(library, sourceType, currentFilePaths, existing, events);
  }

  /**
   * ADR-0023, Entscheidung 4: the executor's own declaration decides whether this run mode may
   * delete by absence - an "ergänzend" run never may, and a caller that tries anyway has a bug this
   * guard makes loud instead of letting it empty an index.
   */
  private static void requireRemoveOnAbsence(
      DocumentSourceType sourceType, SourceIndexingExecutor executor, IndexingRunMode runMode) {
    VanishedDocumentPolicy policy = executor.runModes().get(runMode);
    if (policy != VanishedDocumentPolicy.REMOVE_ON_ABSENCE) {
      throw new IllegalStateException(
          "cleanupVanished called for run mode "
              + runMode
              + " of "
              + sourceType
              + ", whose declared policy is "
              + policy
              + " - only REMOVE_ON_ABSENCE runs may delete by absence");
    }
  }

  private static void logEmptyBestand(KnowledgeLibrary library, DocumentSourceType sourceType) {
    log.info(
        "Skipping stale-document cleanup for library {} ({}) - this run's own bestand is empty,"
            + " which is not distinguishable here from an unreachable or misconfigured source",
        library.getId(),
        sourceType);
  }

  /**
   * Deletes {@code document} and every attachment below it - a source's positive finding that an
   * item is gone, outside the reconciliation. The children come from the repository, not from the
   * caller: an attachment can carry children of its own (a {@code .eml} attached to a page). Each
   * removal is its own {@link IndexingEventCategory#REMOVED} event with {@code message}, deepest
   * first as in {@link #sortedDeepestFirst}; a cyclic {@code parent_document_id} chain is walked
   * once.
   */
  public void removeWithAttachments(
      Document document, IndexingRunEventRecorder events, String message) {
    List<Document> subtree = new ArrayList<>();
    Set<UUID> visited = new HashSet<>();
    Deque<Document> queue = new ArrayDeque<>(List.of(document));
    while (!queue.isEmpty()) {
      Document next = queue.removeFirst();
      if (!visited.add(next.getId())) {
        continue;
      }
      subtree.add(next);
      queue.addAll(documentRepository.findByParentDocumentId(next.getId()));
    }
    for (Document doomed : sortedDeepestFirst(subtree)) {
      remove(doomed, events, message);
    }
  }

  /**
   * Removes every document of {@code existing} whose path is not in {@code currentFilePaths},
   * deepest nesting level first: {@code fk_documents_parent} (ADR-0022, Entscheidung 4) refuses a
   * parent whose children still exist, and {@code findByLibraryIdAndSourceType} carries no {@code
   * ORDER BY} that would guarantee this on its own.
   */
  private int removeVanished(
      KnowledgeLibrary library,
      DocumentSourceType sourceType,
      Set<String> currentFilePaths,
      List<Document> existing,
      IndexingRunEventRecorder events) {
    int removed = 0;
    for (Document document : sortedDeepestFirst(existing)) {
      if (currentFilePaths.contains(document.getFilePath())) {
        continue;
      }
      remove(document, events, REMOVED_MESSAGE);
      removed++;
    }
    if (removed > 0) {
      log.info(
          "Removed {} {} document(s) from library {} no longer present in its source bestand",
          removed,
          sourceType,
          library.getId());
    }
    return removed;
  }

  /** Chunks go before the row; the removal is then its own {@code REMOVED} event. */
  private void remove(Document document, IndexingRunEventRecorder events, String message) {
    vectorChunkStore.deleteByDocumentId(document.getId());
    documentRepository.delete(document);
    events.record(IndexingEventCategory.REMOVED, message, document.getFilePath());
  }

  /**
   * Folds into {@code currentFilePaths} the {@code file_path} of every existing attachment whose
   * parent is present this run but was not re-parsed - the Nachtragsfall of ADR-0022, Entscheidung
   * 3, applied breadth-first from the roots so a grandchild of an unchanged ancestor is preserved
   * regardless of row order. A child of a re-parsed parent survives only if that parent re-reported
   * it. The fold-in step of {@link #reconcile}.
   */
  public static void foldInPreservedAttachmentPaths(
      List<Document> existingDocuments,
      Set<String> currentFilePaths,
      Set<String> reprocessedPaths) {
    Map<UUID, List<Document>> childrenByParentId = new HashMap<>();
    List<Document> roots = new ArrayList<>();
    for (Document candidate : existingDocuments) {
      if (candidate.getParentDocumentId() == null) {
        roots.add(candidate);
      } else {
        childrenByParentId
            .computeIfAbsent(candidate.getParentDocumentId(), id -> new ArrayList<>())
            .add(candidate);
      }
    }
    Deque<Document> queue = new ArrayDeque<>(roots);
    Set<UUID> visited = new HashSet<>();
    while (!queue.isEmpty()) {
      Document parent = queue.removeFirst();
      if (!visited.add(parent.getId())) {
        continue;
      }
      boolean preserveChildren =
          currentFilePaths.contains(parent.getFilePath())
              && !reprocessedPaths.contains(parent.getFilePath());
      for (Document child : childrenByParentId.getOrDefault(parent.getId(), List.of())) {
        if (preserveChildren) {
          currentFilePaths.add(child.getFilePath());
        }
        queue.addLast(child);
      }
    }
  }

  /**
   * Sorts {@code documents} by nesting depth, deepest first: a document with no parent in {@code
   * documents} has depth 0, every other document's depth is one more than its parent's. Ties are
   * left in visiting order - {@code fk_documents_parent} only requires a child before its own
   * parent, not a total order across unrelated documents.
   */
  private static List<Document> sortedDeepestFirst(List<Document> documents) {
    Map<UUID, Document> byId = new HashMap<>();
    for (Document document : documents) {
      byId.put(document.getId(), document);
    }
    Map<UUID, Integer> depthCache = new HashMap<>();
    return documents.stream()
        .sorted(Comparator.comparingInt((Document d) -> depthOf(d, byId, depthCache)).reversed())
        .toList();
  }

  /**
   * The nesting depth of {@code document} within {@code byId} (see {@link #sortedDeepestFirst}),
   * memoized in {@code depthCache} so a shared chain is walked once. {@code visiting} guards a
   * cyclic {@code parentDocumentId} chain - never expected from well-formed data, but a corrupt one
   * must terminate rather than overflow the stack; a document on a cycle counts as its own root.
   */
  private static int depthOf(
      Document document, Map<UUID, Document> byId, Map<UUID, Integer> depthCache) {
    return depthOf(document, byId, depthCache, new HashSet<>());
  }

  private static int depthOf(
      Document document,
      Map<UUID, Document> byId,
      Map<UUID, Integer> depthCache,
      Set<UUID> visiting) {
    Integer cached = depthCache.get(document.getId());
    if (cached != null) {
      return cached;
    }
    UUID parentId = document.getParentDocumentId();
    Document parent = parentId == null ? null : byId.get(parentId);
    int depth;
    if (parent == null || !visiting.add(document.getId())) {
      depth = 0;
    } else {
      depth = 1 + depthOf(parent, byId, depthCache, visiting);
      visiting.remove(document.getId());
    }
    depthCache.put(document.getId(), depth);
    return depth;
  }
}
