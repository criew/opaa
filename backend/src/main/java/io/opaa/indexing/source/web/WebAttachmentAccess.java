package io.opaa.indexing.source.web;

import io.opaa.indexing.AttachmentProgressSink;
import io.opaa.indexing.IndexingEventSink;
import io.opaa.indexing.source.attachment.AttachmentAccess;
import io.opaa.library.KnowledgeLibrary;
import java.util.Set;

/**
 * {@link UrlIndexingExecutor}'s own {@link AttachmentAccess} - the HTTP_DIRECTORY counterpart of
 * {@code FilesystemAttachmentAccess}, with the same contract: {@link #markDeferred()} is a no-op
 * because an HTTP_DIRECTORY run keeps no per-run state whose persistence a lost attachment would
 * need to suppress; a lost attachment is only retried once its parent mail file's own {@code
 * Last-Modified}/checksum changes (or an operator triggers a pipeline re-index).
 *
 * <p>{@code indexedAttachmentPaths}/{@code reprocessedAttachmentPaths} collect what {@link
 * #recordIndexedAttachment} reports across all nesting depths - the executor folds them into its
 * {@code currentUrls}/reprocessed bookkeeping for {@code
 * StaleDocumentCleanupService#cleanupVanished} (ADR-0022, Entscheidung 3).
 */
record WebAttachmentAccess(
    KnowledgeLibrary targetLibrary,
    IndexingEventSink events,
    AttachmentProgressSink progress,
    Set<String> indexedAttachmentPaths,
    Set<String> reprocessedAttachmentPaths)
    implements AttachmentAccess {

  @Override
  public void markDeferred() {
    // See this class' own Javadoc.
  }

  @Override
  public void recordIndexedAttachment(String filePath, boolean reprocessed) {
    indexedAttachmentPaths.add(filePath);
    if (reprocessed) {
      reprocessedAttachmentPaths.add(filePath);
    }
  }
}
