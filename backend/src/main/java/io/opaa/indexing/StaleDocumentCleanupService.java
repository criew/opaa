package io.opaa.indexing;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.library.KnowledgeLibrary;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes documents - and their chunks - that a source no longer contains once an indexing run has
 * finished successfully (#886). A document whose {@code filePath} is missing from {@code
 * currentFilePaths} was not rediscovered by this run, so it no longer exists at the source. Scoped
 * to a single {@code (library, sourceType)} pair (#877): only documents of the source type the
 * caller just ran a full crawl for are considered, and only within that library.
 *
 * <p><b>Callers carry the "successful, uncapped run" invariant, not this class.</b> {@code
 * currentFilePaths} is trusted to be the complete bestand a run discovered; a run that failed, was
 * cancelled, or was truncated by a configured limit (see {@code
 * AutoindexCrawlerService.CrawlResult#truncated}/{@code #incomplete}) must never call {@link
 * #cleanupVanished} at all - its {@code currentFilePaths} would be incomplete, and every document
 * beyond the cut would look vanished. {@link AsyncIndexingExecutor} and {@link UrlIndexingExecutor}
 * enforce this by only calling in on their own success path, after every discovered file has been
 * accounted for. {@link RssFeedIndexingExecutor} deliberately never calls this - an RSS entry
 * scrolling out of the feed's window is not evidence the entry itself is gone (ADR-0017, decision
 * 5).
 *
 * <p><b>An empty {@code currentFilePaths} never deletes anything</b> (#886 review): a run that
 * discovered zero files is indistinguishable here from an unreachable/misconfigured source (an
 * unmounted network share {@code discoverFiles} failed to catch, a web server answering with a
 * maintenance page instead of the real listing) that a caller's own bug let through despite the
 * "successful run" invariant above. Deleting a library's entire bestand on that single,
 * cheap-to-get- wrong signal is not a risk worth taking for the rare case of a genuinely emptied
 * source - this class fails safe instead, and the next run with a real bestand catches up normally.
 */
public class StaleDocumentCleanupService {

  private static final Logger log = LoggerFactory.getLogger(StaleDocumentCleanupService.class);

  private static final String REMOVED_MESSAGE = "In der Quelle nicht mehr gefunden, entfernt";

  private final DocumentRepository documentRepository;
  private final VectorChunkStore vectorChunkStore;

  StaleDocumentCleanupService(
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
   * chunks order (#614): that order exists to close a race with a concurrent {@code
   * uploadTaskExecutor} task re-reading and re-writing the very same row, which cannot happen here
   * - a connector document is never written by that executor. Each removed document is recorded as
   * its own {@link IndexingEventCategory#REMOVED} event via {@code events}, so the run's own
   * protocol names what was removed and not just how many (#886 review).
   *
   * @return the number of documents removed
   */
  int cleanupVanished(
      KnowledgeLibrary library,
      DocumentSourceType sourceType,
      Set<String> currentFilePaths,
      IndexingRunEventRecorder events) {
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
}
