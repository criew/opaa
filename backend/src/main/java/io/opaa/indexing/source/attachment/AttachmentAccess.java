package io.opaa.indexing.source.attachment;

import io.opaa.indexing.AttachmentProgressSink;
import io.opaa.indexing.IndexingEventSink;
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
}
