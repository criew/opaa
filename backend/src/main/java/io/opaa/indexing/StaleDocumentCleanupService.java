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
 * Removes documents - and their chunks - that a source no longer contains once an indexing run has
 * finished successfully. A document whose {@code filePath} is missing from {@code currentFilePaths}
 * was not rediscovered by this run, so it no longer exists at the source. Scoped to a single {@code
 * (library, sourceType)} pair: only documents of the source type the caller just ran a full crawl
 * for are considered, and only within that library.
 *
 * <p><b>Callers carry the "successful, uncapped run" invariant, not this class.</b> {@code
 * currentFilePaths} is trusted to be the complete bestand a run discovered; a run that failed, was
 * cancelled, or was truncated by a configured limit (see {@code
 * AutoindexCrawlerService.CrawlResult#truncated}/{@code #incomplete}) must never call {@link
 * #cleanupVanished} at all - its {@code currentFilePaths} would be incomplete, and every document
 * beyond the cut would look vanished. {@code AsyncIndexingExecutor} and {@code UrlIndexingExecutor}
 * enforce this by only calling in on their own success path, after every discovered file has been
 * accounted for. {@code RssFeedIndexingExecutor} deliberately never calls this - an RSS entry
 * scrolling out of the feed's window is not evidence the entry itself is gone (ADR-0017, decision
 * 5).
 *
 * <p><b>An empty {@code currentFilePaths} never deletes anything</b>: a run that discovered zero
 * files is indistinguishable here from an unreachable/misconfigured source (an unmounted network
 * share {@code discoverFiles} failed to catch, a web server answering with a maintenance page
 * instead of the real listing) that a caller's own bug let through despite the "successful run"
 * invariant above. Deleting a library's entire bestand on that single, cheap-to-get- wrong signal
 * is not a risk worth taking for the rare case of a genuinely emptied source - this class fails
 * safe instead, and the next run with a real bestand catches up normally.
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
   * Deletes every {@code sourceType} document of {@code library} whose {@code filePath} is not in
   * {@code currentFilePaths} - a no-op if {@code currentFilePaths} is empty (see the class
   * Javadoc). Chunks are deleted before the row, mirroring the re-index cleanup order {@link
   * FileProcessingService#processFile}/{@code #processUrlFile} already use for a changed document -
   * deliberately not {@link io.opaa.library.LibraryDocumentService#deleteDocument}'s row-before-
   * chunks order: that order exists to close a race with a concurrent {@code uploadTaskExecutor}
   * task re-reading and re-writing the very same row, which cannot happen here - a connector
   * document is never written by that executor. Each removed document is recorded as its own {@link
   * IndexingEventCategory#REMOVED} event via {@code events}, so the run's own protocol names what
   * was removed and not just how many.
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
    // ADR-0023, Entscheidung 4: the executor's own declaration decides whether this run mode may
    // delete by absence - an "ergänzend" run (RSS, an incremental Confluence run) never may, and a
    // caller that tries anyway has a bug this guard makes loud instead of letting it empty an
    // index.
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
    if (currentFilePaths.isEmpty()) {
      log.info(
          "Skipping stale-document cleanup for library {} ({}) - this run's own bestand is empty,"
              + " which is not distinguishable here from an unreachable or misconfigured source",
          library.getId(),
          sourceType);
      return 0;
    }

    List<Document> existing =
        documentRepository.findByLibraryIdAndSourceType(library.getId(), sourceType);
    // fk_documents_parent (ADR-0022, Entscheidung 4): an attachment removed in the same batch as
    // its own now-vanished parent must be deleted first, or the parent's own delete fails the FK
    // check. findByLibraryIdAndSourceType carries no ORDER BY that would guarantee this on its own
    // - sorted here instead, deepest nesting level first: a grandchild (a Mail-in-Mail
    // attachment's own attachment) is deleted before its intermediate parent, which is deleted
    // before the outermost parent.
    existing = sortedDeepestFirst(existing);
    int removed = 0;
    for (Document document : existing) {
      if (currentFilePaths.contains(document.getFilePath())) {
        continue;
      }
      vectorChunkStore.deleteByDocumentId(document.getId());
      documentRepository.delete(document);
      events.record(IndexingEventCategory.REMOVED, REMOVED_MESSAGE, document.getFilePath());
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

  /**
   * Folds into {@code currentFilePaths} the {@code file_path} of every existing attachment row
   * whose parent is present this run ({@code currentFilePaths}) but was <em>not</em> re-parsed
   * ({@code reprocessedPaths}) - the Nachtragsfall of ADR-0022, Entscheidung 3, applied
   * breadth-first from the roots down so a grandchild of an unchanged (or merely
   * checksum-confirmed) ancestor is preserved deterministically, regardless of row order. A child
   * of a re-parsed parent is only present via the attachment path's own recording; one it did not
   * re-report stays out and is cleaned up as vanished. Shared by every executor that pairs the
   * generalized attachment path with {@link #cleanupVanished} - FILESYSTEM and HTTP_DIRECTORY
   * today.
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
   * memoized in {@code depthCache} so a chain shared by several documents (e.g. every grandchild of
   * the same Mail-in-Mail root) is only walked once. {@code visiting} guards against a cyclic
   * {@code parentDocumentId} chain - never expected from well-formed data, but a corrupt or
   * adversarial one must terminate rather than stack-overflow; a document on a detected cycle is
   * treated as its own root (depth 0) rather than propagating the cycle further.
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
