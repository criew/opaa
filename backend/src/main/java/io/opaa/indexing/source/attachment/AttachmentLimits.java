package io.opaa.indexing.source.attachment;

/**
 * How many attachments of one parent {@link AttachmentIndexer} indexes and how large a downloaded
 * one may be - plain values, so a connector supplies its own numbers without this package depending
 * on its configuration type; {@link AttachmentProperties#limits()} is the shared default.
 *
 * @param maxPerParent the maximum number of attachments indexed per parent document
 * @param maxSizeBytes the maximum number of bytes read from a single {@link
 *     AttachmentSource.Download}; a {@link AttachmentSource.LocalFile} was bounded by whoever
 *     produced it
 */
public record AttachmentLimits(int maxPerParent, long maxSizeBytes) {}
