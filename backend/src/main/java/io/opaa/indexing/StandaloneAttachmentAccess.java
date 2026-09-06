package io.opaa.indexing;

import io.opaa.indexing.source.attachment.AttachmentAccess;
import io.opaa.library.KnowledgeLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link AttachmentAccess} for a caller with no run/job of its own: a single document upload
 * ({@code io.opaa.library.LibraryDocumentService}) and the operator-triggered pipeline re-index
 * ({@code PipelineReindexService}). Events are only logged, progress is a no-op, and {@link
 * #markDeferred()} has no per-run state to suppress - a lost attachment is retried the next time
 * its parent is re-processed (a re-upload, a pipeline re-index). {@code recordIndexedAttachment}
 * keeps its no-op default: neither caller reconciles by absence.
 */
public record StandaloneAttachmentAccess(KnowledgeLibrary targetLibrary, String logContext)
    implements AttachmentAccess {

  private static final Logger log = LoggerFactory.getLogger(StandaloneAttachmentAccess.class);

  @Override
  public IndexingEventSink events() {
    return (category, message, reference) ->
        log.info("{} attachment event [{}]: {} ({})", logContext, category, message, reference);
  }

  @Override
  public AttachmentProgressSink progress() {
    return outcome -> {};
  }

  @Override
  public void markDeferred() {
    // See this class' own Javadoc.
  }
}
