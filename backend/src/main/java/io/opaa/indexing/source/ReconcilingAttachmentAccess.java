package io.opaa.indexing.source;

import io.opaa.indexing.AttachmentProgressSink;
import io.opaa.indexing.IndexingEventSink;
import io.opaa.indexing.SourceDocumentContext;
import io.opaa.indexing.source.attachment.AttachmentAccess;
import io.opaa.library.KnowledgeLibrary;

/**
 * The {@link AttachmentAccess} of an {@link IndexingRun} that reconciles by absence (ADR-0022,
 * Entscheidung 8): every attachment the shared attachment path reports enters the run's
 * reconciliation set, and every attachment stored through it inherits {@code context} - a
 * Confluence page's space and hierarchy, {@link SourceDocumentContext#NONE} for a source without
 * that notion.
 *
 * <p>{@link #markDeferred()} suppresses nothing: such a run keeps no conditional-GET state, and a
 * lost attachment is retried once its parent is re-processed. The attachment path counts each
 * attachment's outcome itself, through {@link #progress()}.
 */
public final class ReconcilingAttachmentAccess implements AttachmentAccess {

  private final IndexingRun run;
  private final SourceDocumentContext context;

  ReconcilingAttachmentAccess(IndexingRun run, SourceDocumentContext context) {
    this.run = run;
    this.context = context;
  }

  @Override
  public KnowledgeLibrary targetLibrary() {
    return run.library();
  }

  @Override
  public IndexingEventSink events() {
    return run.events();
  }

  @Override
  public AttachmentProgressSink progress() {
    return run.progress();
  }

  @Override
  public void markDeferred() {
    // See this class' own Javadoc.
  }

  @Override
  public void recordIndexedAttachment(String filePath, boolean reprocessed) {
    if (reprocessed) {
      run.markReprocessed(filePath);
    } else {
      run.markPresent(filePath);
    }
  }

  @Override
  public SourceDocumentContext sourceContext() {
    return context;
  }
}
