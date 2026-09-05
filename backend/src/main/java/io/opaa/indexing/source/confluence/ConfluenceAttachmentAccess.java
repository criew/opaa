package io.opaa.indexing.source.confluence;

import io.opaa.indexing.AttachmentProgressSink;
import io.opaa.indexing.IndexingEventSink;
import io.opaa.indexing.IndexingRunProgress;
import io.opaa.indexing.SourceDocumentContext;
import io.opaa.indexing.source.attachment.AttachmentAccess;
import io.opaa.library.KnowledgeLibrary;
import java.util.Set;

/**
 * {@link ConfluenceIndexingExecutor}'s own {@link AttachmentAccess} (ADR-0022, Entscheidung 8), one
 * instance per page: unlike RSS's run-wide context it carries the page's {@link #sourceContext()},
 * so every attachment stored through it, at any nesting depth, is placed where its page sits.
 *
 * <p>{@link #markDeferred()} suppresses nothing - a Confluence run has no conditional GET, and a
 * lost attachment is retried next run anyway; the flag only lets the executor count it as failed
 * rather than skipped. {@code currentPaths}/{@code reprocessedPaths} collect what {@link
 * #recordIndexedAttachment} reports, which the executor folds into the reconciliation set
 * (Entscheidung 3).
 */
final class ConfluenceAttachmentAccess implements AttachmentAccess {

  private final KnowledgeLibrary library;
  private final IndexingEventSink events;
  private final IndexingRunProgress progress;
  private final SourceDocumentContext context;
  private final Set<String> currentPaths;
  private final Set<String> reprocessedPaths;
  private boolean anyProcessed;
  private boolean anyDeferred;

  ConfluenceAttachmentAccess(
      KnowledgeLibrary library,
      IndexingEventSink events,
      IndexingRunProgress progress,
      SourceDocumentContext context,
      Set<String> currentPaths,
      Set<String> reprocessedPaths) {
    this.library = library;
    this.events = events;
    this.progress = progress;
    this.context = context;
    this.currentPaths = currentPaths;
    this.reprocessedPaths = reprocessedPaths;
  }

  @Override
  public KnowledgeLibrary targetLibrary() {
    return library;
  }

  @Override
  public IndexingEventSink events() {
    return events;
  }

  /** Every attachment document created counts towards the run's attachment share. */
  @Override
  public AttachmentProgressSink progress() {
    return () -> {
      anyProcessed = true;
      progress.recordAttachment(IndexingRunProgress.AttachmentOutcome.PROCESSED);
    };
  }

  @Override
  public void markDeferred() {
    anyDeferred = true;
  }

  @Override
  public void recordIndexedAttachment(String filePath, boolean reprocessed) {
    currentPaths.add(filePath);
    if (reprocessed) {
      reprocessedPaths.add(filePath);
    }
  }

  @Override
  public SourceDocumentContext sourceContext() {
    return context;
  }

  /** Whether at least one attachment document was created through this access. */
  boolean anyProcessed() {
    return anyProcessed;
  }

  /** Whether the attachment path deferred or failed at least one attachment through this access. */
  boolean anyDeferred() {
    return anyDeferred;
  }
}
