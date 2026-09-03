package io.opaa.indexing.source.filesystem;

import io.opaa.indexing.AttachmentProgressSink;
import io.opaa.indexing.IndexingEventSink;
import io.opaa.indexing.source.attachment.AttachmentAccess;
import io.opaa.library.KnowledgeLibrary;
import java.util.Set;

/**
 * {@link AsyncIndexingExecutor}'s own {@link AttachmentAccess} (#1183) - the FILESYSTEM counterpart
 * of {@code RssFeedRunContext}. {@link #markDeferred()} is a no-op: unlike RSS's conditional-GET
 * feed state, a FILESYSTEM run has no per-run state whose persistence a lost attachment needs to
 * suppress. A lost attachment is, however, only retried once its parent mail file's own checksum
 * changes (or an operator triggers a pipeline re-index) - an unchanged parent is skipped without
 * re-extracting its attachments, so the next scheduled run does <em>not</em> retry it by itself.
 *
 * <p>{@code indexedAttachmentPaths}/{@code reprocessedAttachmentPaths} collect what {@link
 * #recordIndexedAttachment} reports across all nesting depths - the executor folds them into its
 * {@code currentFilePaths}/reprocessed bookkeeping for {@code
 * StaleDocumentCleanupService#cleanupVanished} (ADR-0022, Entscheidung 3).
 */
record FilesystemAttachmentAccess(
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
