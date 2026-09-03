package io.opaa.indexing.source.attachment;

/**
 * The source-agnostic counterpart to the download-side fields of {@code IndexingProperties.Rss}
 * (ADR-0022, Entscheidung 8) - {@link AttachmentIndexer} takes these as plain values instead of the
 * RSS-specific properties record, so a future connector (Confluence) supplies its own numbers
 * without this package depending on RSS's configuration type.
 *
 * @param maxPerParent the maximum number of attachments indexed per parent document
 * @param maxAttachmentSizeBytes the maximum number of bytes read from a single {@link
 *     AttachmentSource.Download}
 * @param requestDelayMs the minimum delay, in milliseconds, applied before each {@link
 *     AttachmentSource.Download} request - a no-op for {@link AttachmentSource.LocalFile}, which
 *     makes no request of its own
 * @param userAgent the {@code User-Agent} header sent with every {@link AttachmentSource.Download}
 *     request
 * @param maxAttachmentDepth how many levels of nested attachment-in-attachment recursion {@link
 *     AttachmentIndexer} descends into before it stops and skips the remainder (ADR-0022,
 *     Entscheidung 6) - the generalized counterpart of {@code MailProperties#maxAttachmentDepth()},
 *     which owned this limit before #1183 moved it here: once a discovered attachment (an inner
 *     {@code .eml}, a nested archive) is itself routed back through {@link
 *     io.opaa.indexing.FileProcessingService#processUrlFile}, its own pipeline can report further
 *     {@code discoveredAttachments}, recursing into this class again on the same thread (see {@link
 *     AttachmentIndexer}'s own Javadoc for the depth counter).
 */
public record AttachmentDownloadLimits(
    int maxPerParent,
    long maxAttachmentSizeBytes,
    long requestDelayMs,
    String userAgent,
    int maxAttachmentDepth) {}
