package io.opaa.indexing.source;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.job.IndexingRunEventRecorder;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.util.Set;

/**
 * The removals of a run: the reconciliation by absence {@code IndexingRunTemplate} runs after a
 * complete listing in a {@link VanishedDocumentPolicy#REMOVE_ON_ABSENCE} mode, keeping the
 * attachments of a parent that was not re-processed, and the removal of one document on the
 * source's own positive finding, in any run mode ({@code IndexingRun#removeWithAttachments}).
 * Implemented by {@code io.opaa.indexing.maintenance.StaleDocumentCleanupService}.
 */
public interface VanishedDocumentReconciler {

  /**
   * Reconciles {@code library} against {@code currentPaths}, every {@code file_path} the run met;
   * {@code reprocessedPaths} are the parents whose attachments were enumerated afresh.
   *
   * @return the number of documents removed
   */
  int reconcile(
      KnowledgeLibrary library,
      SourceType sourceType,
      Set<String> currentPaths,
      Set<String> reprocessedPaths,
      IndexingRunEventRecorder events,
      SourceIndexingExecutor executor,
      IndexingRunMode runMode);

  /** Removes {@code document} and every attachment below it, see {@code IndexingRun}. */
  void removeWithAttachments(Document document, IndexingRunEventRecorder events, String message);
}
