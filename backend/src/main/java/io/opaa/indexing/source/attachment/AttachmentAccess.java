package io.opaa.indexing.source.attachment;

import io.opaa.indexing.AttachmentProgressSink;
import io.opaa.indexing.IndexingEventSink;
import io.opaa.indexing.SourceDocumentContext;
import io.opaa.library.KnowledgeLibrary;

/**
 * The narrow, source-agnostic slice of a connector run {@link AttachmentIndexer} needs (ADR-0022,
 * Entscheidung 8) - replaces the direct dependency on RSS's own {@code RssFeedRunContext}/{@code
 * RssPoliteness}. A connector implements (or adapts to) this interface instead of this package
 * depending on a single source's run-state type; {@code RssFeedRunContext} implements it directly
 * today. {@link #events()}/{@link #progress()} are declared against the narrow {@link
 * IndexingEventSink}/{@link AttachmentProgressSink} interfaces (#1183), not the full, job-bound
 * {@code IndexingRunEventRecorder}/{@code IndexingRunProgress} - a caller with no run/job of its
 * own (a single document upload, {@code FileProcessingService#processStoredFile}) supplies a
 * lightweight implementation instead of standing up a job it does not have.
 */
public interface AttachmentAccess {

  KnowledgeLibrary targetLibrary();

  IndexingEventSink events();

  AttachmentProgressSink progress();

  /**
   * Marks the run as having deferred or failed at least one item - the caller's own contract
   * decides what that suppresses (for RSS: persisting the feed's conditional-GET state, see {@code
   * RssFeedIndexingExecutor#execute}).
   */
  void markDeferred();

  /**
   * Called once for every attachment {@link AttachmentIndexer} encountered in the parent this run,
   * at any nesting depth - created, confirmed unchanged, or present but failed (quota, rejected,
   * transient read error) - the channel a caller that runs {@code
   * StaleDocumentCleanupService#cleanupVanished} uses to fold the attachment's {@code file_path}
   * into its {@code currentFilePaths} (ADR-0022, Entscheidung 3): only an attachment actually
   * removed from its parent goes unreported and falls away. {@code reprocessed} is {@code true}
   * when the attachment's content was actually re-parsed this call (so its own child attachments
   * were freshly enumerated too) and {@code false} otherwise (checksum-confirmed or failed - its
   * existing children were <em>not</em> rediscovered and must be preserved from the database
   * instead). Default no-op for a caller that never cleans up by path (RSS).
   */
  default void recordIndexedAttachment(String filePath, boolean reprocessed) {}

  /**
   * Where inside its source the parent document sits (ADR-0023) - inherited by every attachment
   * {@link AttachmentIndexer} stores through this access, at any nesting depth: a Confluence
   * attachment carries its page's space and hierarchy path, so the run protocol, the citation and
   * the chunk context can name them. Default {@link SourceDocumentContext#NONE} for every source
   * without such a notion (RSS, Mail, HTTP_DIRECTORY, an upload).
   */
  default SourceDocumentContext sourceContext() {
    return SourceDocumentContext.NONE;
  }
}
