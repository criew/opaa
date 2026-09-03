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
 */
public record AttachmentDownloadLimits(
    int maxPerParent, long maxAttachmentSizeBytes, long requestDelayMs, String userAgent) {}
