package io.opaa.indexing.source.filesystem;

import io.opaa.indexing.AttachmentProgressSink;
import io.opaa.indexing.IndexingEventSink;
import io.opaa.indexing.source.attachment.AttachmentAccess;
import io.opaa.library.KnowledgeLibrary;

/**
 * {@link AsyncIndexingExecutor}'s own {@link AttachmentAccess} (#1183) - the FILESYSTEM/UPLOAD
 * counterpart of {@code RssFeedRunContext}. {@link #markDeferred()} is a no-op: unlike RSS's
 * conditional-GET feed state, a FILESYSTEM run has no per-run state whose persistence a lost
 * attachment needs to suppress - the next scheduled run simply re-discovers the same file and
 * retries the attachment the same way it retries any other failed document.
 */
record FilesystemAttachmentAccess(
    KnowledgeLibrary targetLibrary, IndexingEventSink events, AttachmentProgressSink progress)
    implements AttachmentAccess {

  @Override
  public void markDeferred() {
    // See this class' own Javadoc.
  }
}
